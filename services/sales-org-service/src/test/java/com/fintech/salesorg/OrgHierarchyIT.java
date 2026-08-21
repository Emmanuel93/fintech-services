package com.fintech.salesorg;

import com.fintech.salesorg.application.port.in.CreateOrgUnitCommand;
import com.fintech.salesorg.application.port.in.OrgUnitUseCase;
import com.fintech.salesorg.application.port.out.OrgLevelRepository;
import com.fintech.salesorg.application.port.out.OrgUnitRepository;
import com.fintech.salesorg.domain.InvalidHierarchyException;
import com.fintech.salesorg.domain.OrgLevel;
import com.fintech.salesorg.domain.OrgUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * El árbol contra Postgres real. Prueba lo que un test con mocks no puede: que las migraciones
 * aplican (extensión ltree + índice GIST funcional), que Hibernate valida el mapeo contra el esquema
 * real, y —lo central— que el subárbol se resuelve con el operador LTREE {@code <@} en UNA consulta,
 * cualquiera sea la profundidad. Ese "una consulta, no N" es el invariante que sostiene el alcance.
 */
@SpringBootTest
@Testcontainers
class OrgHierarchyIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("fintech")
            .withUsername("fintech")
            .withPassword("fintech");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:9999");
    }

    @MockBean KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired OrgUnitUseCase orgUnitUseCase;
    @Autowired OrgLevelRepository levelRepository;
    @Autowired OrgUnitRepository unitRepository;

    @Test
    void migrationsAndSeedLoad_defaultLadderPresent() {
        // Que el contexto arranque ya prueba: ltree se instala, el índice GIST funcional se crea y
        // Hibernate valida el mapeo (path TEXT) contra el esquema real.
        assertThat(levelRepository.findByCode("NATIONAL")).isPresent();
        assertThat(levelRepository.findByCode("REGION")).isPresent();
        assertThat(levelRepository.findByCode("ZONE")).isPresent();
        assertThat(levelRepository.findByCode("BRANCH")).isPresent();
    }

    @Test
    void subtreeQuery_returnsDescendantsOfAnyDepth_viaLtree() {
        int nat = depthOf("NATIONAL");
        int reg = depthOf("REGION");
        int zon = depthOf("ZONE");

        // Raíz propia para aislarse del contenedor compartido: TESTROOT → TR_NORTE → TR_MTY.
        String suffix = uniq();
        OrgUnit root = create(levelId("NATIONAL"), null, "TESTROOT" + suffix);
        OrgUnit norte = create(levelId("REGION"), root.getUnitId(), "TRNORTE" + suffix);
        OrgUnit mty = create(levelId("ZONE"), norte.getUnitId(), "TRMTY" + suffix);

        // Los paths se materializan encadenando códigos.
        assertThat(root.getPath()).isEqualTo("TESTROOT" + suffix);
        assertThat(norte.getPath()).isEqualTo("TESTROOT" + suffix + ".TRNORTE" + suffix);
        assertThat(mty.getPath()).isEqualTo("TESTROOT" + suffix + ".TRNORTE" + suffix + ".TRMTY" + suffix);
        assertThat(nat).isLessThan(reg);
        assertThat(reg).isLessThan(zon);

        // Subárbol desde la raíz: la raíz y sus dos descendientes, sin importar la profundidad.
        assertThat(orgUnitUseCase.subtree(root.getUnitId()))
                .extracting(OrgUnit::getCode)
                .containsExactlyInAnyOrder("TESTROOT" + suffix, "TRNORTE" + suffix, "TRMTY" + suffix);

        // Subárbol desde el nodo intermedio: él y su descendiente; NO su ancestro.
        assertThat(orgUnitUseCase.subtree(norte.getUnitId()))
                .extracting(OrgUnit::getCode)
                .containsExactlyInAnyOrder("TRNORTE" + suffix, "TRMTY" + suffix);

        // Hijos directos: solo el nivel inmediato.
        assertThat(orgUnitUseCase.children(norte.getUnitId()))
                .extracting(OrgUnit::getCode)
                .containsExactly("TRMTY" + suffix);
    }

    @Test
    void distributorChain_extendsLadderBelowBranch_andPartyRefResolves() {
        // Escalera completa hasta distribuidor: MX → REGIÓN → ZONA → SUCURSAL → EJECUTIVO → DISTRIBUIDOR.
        String s = uniq();
        OrgUnit root   = create(levelId("NATIONAL"), null, "DROOT" + s);
        OrgUnit region = create(levelId("REGION"), root.getUnitId(), "DREG" + s);
        OrgUnit zone   = create(levelId("ZONE"), region.getUnitId(), "DZON" + s);
        OrgUnit branch = create(levelId("BRANCH"), zone.getUnitId(), "DSUC" + s);
        UUID staffId = UUID.randomUUID();
        UUID distPartyId = UUID.randomUUID();
        OrgUnit exec = createNode(levelId("EXECUTIVE"), branch.getUnitId(), "DEXE" + s, staffId);
        OrgUnit dist = createNode(levelId("DISTRIBUTOR"), exec.getUnitId(), "DDIS" + s, distPartyId);

        // El nodo enlaza a su persona.
        assertThat(exec.getPartyRef()).isEqualTo(staffId);
        assertThat(dist.getPartyRef()).isEqualTo(distPartyId);
        assertThat(dist.getPath()).endsWith(".DEXE" + s + ".DDIS" + s);

        // El alcance del ROOT baja hasta el distribuidor con la misma consulta LTREE.
        assertThat(orgUnitUseCase.subtree(root.getUnitId()))
                .extracting(OrgUnit::getCode)
                .contains("DEXE" + s, "DDIS" + s);

        // Se localiza el nodo del distribuidor por su party (lo usará el rollup de cartera).
        assertThat(unitRepository.findByPartyRef(distPartyId))
                .extracting(OrgUnit::getUnitId).containsExactly(dist.getUnitId());

        // Alcance: los distribuidores del subárbol (JOIN org_units+org_levels sobre el path LTREE).
        // Desde el root y desde el ejecutivo se ve; desde la sucursal (sobre el ejecutivo) también.
        assertThat(orgUnitUseCase.distributorsInSubtree(root.getUnitId())).contains(distPartyId);
        assertThat(orgUnitUseCase.distributorsInSubtree(exec.getUnitId())).contains(distPartyId);
        assertThat(orgUnitUseCase.executivesInSubtree(branch.getUnitId())).contains(staffId);

        // Un distribuidor NO puede colgar directo de una sucursal (salta el nivel ejecutivo).
        assertThatThrownBy(() -> createNode(levelId("DISTRIBUTOR"), branch.getUnitId(), "DBAD" + s, distPartyId))
                .isInstanceOf(InvalidHierarchyException.class);

        // Resolución de promoterCode (DS-4): el código del distribuidor → su partyId; inexistente → vacío.
        assertThat(orgUnitUseCase.resolveDistributorByCode("DDIS" + s)).contains(distPartyId);
        assertThat(orgUnitUseCase.resolveDistributorByCode("NOPE" + s)).isEmpty();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private OrgUnit create(UUID levelId, UUID parentId, String code) {
        return orgUnitUseCase.create(new CreateOrgUnitCommand(levelId, parentId, code, code, null, "it"));
    }

    private OrgUnit createNode(UUID levelId, UUID parentId, String code, UUID partyRef) {
        return orgUnitUseCase.create(new CreateOrgUnitCommand(levelId, parentId, code, code, partyRef, "it"));
    }

    private UUID levelId(String code) {
        return levelRepository.findByCode(code).map(OrgLevel::getLevelId).orElseThrow();
    }

    private int depthOf(String code) {
        return levelRepository.findByCode(code).map(OrgLevel::getDepth).orElseThrow();
    }

    private static String uniq() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}

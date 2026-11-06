package com.fintech.salesorg;

import com.fintech.salesorg.application.EffectivePlacementPolicy;
import com.fintech.salesorg.application.port.in.CreateOrgUnitCommand;
import com.fintech.salesorg.application.port.in.OrgUnitUseCase;
import com.fintech.salesorg.application.port.in.PlacementPolicyUseCase;
import com.fintech.salesorg.application.port.out.OrgLevelRepository;
import com.fintech.salesorg.application.port.out.OrgUnitRepository;
import com.fintech.salesorg.application.port.out.ProductEnvelopePort;
import com.fintech.salesorg.domain.ClientLimits;
import com.fintech.salesorg.domain.ClientType;
import com.fintech.salesorg.domain.InvalidHierarchyException;
import com.fintech.salesorg.domain.InvalidPlacementPolicyException;
import com.fintech.salesorg.domain.OrgLevel;
import com.fintech.salesorg.domain.OrgUnit;
import com.fintech.salesorg.domain.PlacementPolicy;
import com.fintech.salesorg.domain.ProductEnvelope;
import com.fintech.salesorg.infrastructure.adapter.out.messaging.KafkaPlacementPolicyEventPublisher;
import com.fintech.salesorg.infrastructure.adapter.out.messaging.PlacementPolicyChangedPayload;
import org.mockito.ArgumentCaptor;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

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
    // The catalog is not part of this container: the line's envelope is what the policy is validated against.
    @MockBean ProductEnvelopePort products;

    @Autowired OrgUnitUseCase orgUnitUseCase;
    @Autowired PlacementPolicyUseCase placementPolicies;
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

    @Test
    void placementPolicy_isInheritedDownTheRealTree_andADistributorOnlyTightensIt() {
        given(products.distributorLine()).willReturn(Optional.of(
                new ProductEnvelope("DL-001", new BigDecimal("1000"), new BigDecimal("10000"), 12, 24)));
        String s = uniq();
        OrgUnit root   = create(levelId("NATIONAL"), null, "PROOT" + s);
        OrgUnit region = create(levelId("REGION"), root.getUnitId(), "PREG" + s);
        OrgUnit zone   = create(levelId("ZONE"), region.getUnitId(), "PZON" + s);
        OrgUnit branch = create(levelId("BRANCH"), zone.getUnitId(), "PSUC" + s);
        OrgUnit exec   = createNode(levelId("EXECUTIVE"), branch.getUnitId(), "PEXE" + s, UUID.randomUUID());
        UUID herParty = UUID.randomUUID();
        OrgUnit dist   = createNode(levelId("DISTRIBUTOR"), exec.getUnitId(), "PDIS" + s, herParty);

        // The ancestors query: the chain from the root, in order, with one indexed LTREE query.
        assertThat(unitRepository.findAncestorsInclusive(dist.getPath()))
                .extracting(OrgUnit::getCode)
                .containsExactly("PROOT" + s, "PREG" + s, "PZON" + s, "PSUC" + s, "PEXE" + s, "PDIS" + s);

        // Nothing configured yet: the distributor inherits nothing.
        assertThat(placementPolicies.effective(dist.getCode()).configured()).isFalse();

        // The branch declares; the distributor two levels down receives it resolved.
        PlacementPolicy branchPolicy = PlacementPolicy.of(
                new ClientLimits(new BigDecimal("5000.00"), new BigDecimal("5000.00"), 1, 16),
                new ClientLimits(new BigDecimal("10000.00"), new BigDecimal("15000.00"), 3, 24));
        placementPolicies.declare(branch.getCode(), branchPolicy, "it");
        EffectivePlacementPolicy hers = placementPolicies.effective(dist.getCode());
        assertThat(hers.effective()).isEqualTo(branchPolicy);
        assertThat(hers.inheritedFrom()).isEqualTo(branch.getCode());
        assertThat(hers.partyRef()).isEqualTo(herParty);
        assertThat(hers.levelCode()).isEqualTo("DISTRIBUTOR");

        // She may not widen it; she may tighten it.
        assertThatThrownBy(() -> placementPolicies.declare(dist.getCode(),
                PlacementPolicy.of(new ClientLimits(new BigDecimal("8000"), null, null, null), null), "it"))
                .isInstanceOf(InvalidPlacementPolicyException.class);
        placementPolicies.declare(dist.getCode(), PlacementPolicy.of(new ClientLimits(new BigDecimal("3000.00"), null, null, 12), null), "it");
        assertThat(placementPolicies.effective(dist.getCode()).effective().forType(ClientType.NUEVO))
                .isEqualTo(new ClientLimits(new BigDecimal("3000.00"), new BigDecimal("5000.00"), 1, 12));

        // A distributor created afterwards under the branch gets her copy published on creation.
        UUID laterParty = UUID.randomUUID();
        OrgUnit later = createNode(levelId("DISTRIBUTOR"), exec.getUnitId(), "PDI2" + s, laterParty);
        ArgumentCaptor<Object> payloads = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate, atLeastOnce()).send(eq(KafkaPlacementPolicyEventPublisher.TOPIC), eq(later.getCode()), payloads.capture());
        PlacementPolicyChangedPayload published = (PlacementPolicyChangedPayload) payloads.getValue();
        assertThat(published.configured()).isTrue();
        assertThat(published.partyRef()).isEqualTo(laterParty);
        assertThat(published.inheritedFrom()).isEqualTo(branch.getCode());
        assertThat(published.nuevo().maxLoanAmount()).isEqualByComparingTo("5000");
        assertThat(published.revolvente().maxTerm()).isEqualTo(24);

        // Clearing the branch leaves the tree without a policy again, and says so to every unit below.
        placementPolicies.clear(branch.getCode(), "it");
        assertThat(placementPolicies.effective(later.getCode()).configured()).isFalse();
        // Her own declaration survives the branch's clearing: it is hers.
        assertThat(placementPolicies.effective(dist.getCode()).effective().forType(ClientType.NUEVO))
                .isEqualTo(new ClientLimits(new BigDecimal("3000.00"), null, null, 12));
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

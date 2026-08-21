package com.fintech.salesorg;

import com.fintech.salesorg.application.port.in.AssignToUnitCommand;
import com.fintech.salesorg.application.port.in.CreateOrgUnitCommand;
import com.fintech.salesorg.application.port.in.ManageAssignmentsUseCase;
import com.fintech.salesorg.application.port.in.OrgUnitUseCase;
import com.fintech.salesorg.application.port.out.OrgLevelRepository;
import com.fintech.salesorg.domain.AssigneeType;
import com.fintech.salesorg.domain.OrgLevel;
import com.fintech.salesorg.domain.OrgUnit;
import com.fintech.salesorg.domain.UnitAssignment;
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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asignaciones contra Postgres real. Prueba lo que un mock no puede: que reasignar respeta el índice
 * único parcial ("una activa por asignado") gracias a cerrar-antes-de-insertar, y que el alcance de
 * una unidad se resuelve uniendo asignaciones con el subárbol LTREE en una sola consulta.
 */
@SpringBootTest
@Testcontainers
class UnitAssignmentIT {

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
    @Autowired ManageAssignmentsUseCase assignments;
    @Autowired OrgLevelRepository levelRepository;

    @Test
    void reassign_keepsExactlyOneActive_andPreservesHistory() {
        String s = uniq();
        OrgUnit unitA = create(levelId("NATIONAL"), null, "AAROOT" + s);
        OrgUnit unitB = create(levelId("NATIONAL"), null, "BBROOT" + s);
        UUID staff = UUID.randomUUID();

        assignments.assign(new AssignToUnitCommand(unitA.getUnitId(), AssigneeType.STAFF, staff, "MEMBER", "it"));
        // Reasignar al mismo empleado: si el índice único parcial no se respetara, esto reventaría.
        assignments.assign(new AssignToUnitCommand(unitB.getUnitId(), AssigneeType.STAFF, staff, "MANAGER", "it"));

        // Exactamente una vigente, y es la última (unitB).
        assertThat(assignments.currentAssignment(AssigneeType.STAFF, staff))
                .get()
                .satisfies(a -> {
                    assertThat(a.getUnitId()).isEqualTo(unitB.getUnitId());
                    assertThat(a.getAssignmentRole()).isEqualTo("MANAGER");
                    assertThat(a.isActive()).isTrue();
                });
        // Historial: dos filas, una cerrada (unitA) y una vigente (unitB).
        assertThat(assignments.history(AssigneeType.STAFF, staff)).hasSize(2);
        assertThat(assignments.history(AssigneeType.STAFF, staff))
                .filteredOn(UnitAssignment::isActive).hasSize(1);
    }

    @Test
    void scopeOfUnit_gathersAssignmentsAcrossTheSubtree() {
        String s = uniq();
        OrgUnit root  = create(levelId("NATIONAL"), null, "SROOT" + s);
        OrgUnit norte = create(levelId("REGION"), root.getUnitId(), "SNORTE" + s);
        OrgUnit mty   = create(levelId("ZONE"), norte.getUnitId(), "SMTY" + s);

        UUID staffAtMty   = UUID.randomUUID();
        UUID staffAtNorte = UUID.randomUUID();
        assignments.assign(new AssignToUnitCommand(mty.getUnitId(), AssigneeType.STAFF, staffAtMty, "MEMBER", "it"));
        assignments.assign(new AssignToUnitCommand(norte.getUnitId(), AssigneeType.STAFF, staffAtNorte, "MANAGER", "it"));

        // Alcance de la zona (hoja): solo su gente.
        assertThat(assignments.scopeOfUnit(mty.getUnitId()))
                .extracting(UnitAssignment::getAssigneeId)
                .containsExactly(staffAtMty);

        // Alcance de la región: su gente y la de su subárbol (la zona).
        assertThat(assignments.scopeOfUnit(norte.getUnitId()))
                .extracting(UnitAssignment::getAssigneeId)
                .containsExactlyInAnyOrder(staffAtNorte, staffAtMty);

        // Alcance de la raíz propia: todos.
        assertThat(assignments.scopeOfUnit(root.getUnitId()))
                .extracting(UnitAssignment::getAssigneeId)
                .containsExactlyInAnyOrder(staffAtNorte, staffAtMty);
    }

    private OrgUnit create(UUID levelId, UUID parentId, String code) {
        return orgUnitUseCase.create(new CreateOrgUnitCommand(levelId, parentId, code, code, null, "it"));
    }

    private UUID levelId(String code) {
        return levelRepository.findByCode(code).map(OrgLevel::getLevelId).orElseThrow();
    }

    private static String uniq() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}

package com.fintech.salesorg.application.service;

import com.fintech.salesorg.application.port.in.AssignToUnitCommand;
import com.fintech.salesorg.application.port.out.OrgUnitRepository;
import com.fintech.salesorg.application.port.out.UnitAssignmentRepository;
import com.fintech.salesorg.domain.AssigneeType;
import com.fintech.salesorg.domain.OrgUnit;
import com.fintech.salesorg.domain.OrgUnitNotFoundException;
import com.fintech.salesorg.domain.UnitAssignment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class UnitAssignmentServiceTest {

    @Mock UnitAssignmentRepository assignmentRepository;
    @Mock OrgUnitRepository unitRepository;

    UnitAssignmentService service;

    final UUID unitId = UUID.randomUUID();
    final UUID staffId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new UnitAssignmentService(assignmentRepository, unitRepository);
        lenient().when(assignmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void assign_closesPriorActiveBeforeCreatingNew() {
        given(unitRepository.findById(unitId)).willReturn(Optional.of(
                OrgUnit.create(UUID.randomUUID(), null, "MX", "México", "MX", null, "sys")));
        given(assignmentRepository.endActive(eq(AssigneeType.STAFF), eq(staffId), any())).willReturn(1);

        UnitAssignment saved = service.assign(new AssignToUnitCommand(
                unitId, AssigneeType.STAFF, staffId, "MANAGER", "admin-1"));

        // El orden es el invariante: cerrar la vigente ANTES de insertar la nueva.
        InOrder order = inOrder(assignmentRepository);
        order.verify(assignmentRepository).endActive(eq(AssigneeType.STAFF), eq(staffId), any(Instant.class));
        order.verify(assignmentRepository).save(any(UnitAssignment.class));
        assertThat(saved.getUnitId()).isEqualTo(unitId);
        assertThat(saved.getAssignmentRole()).isEqualTo("MANAGER");
        assertThat(saved.isActive()).isTrue();
    }

    @Test
    void assign_unitNotFound_throws_andDoesNotTouchAssignments() {
        given(unitRepository.findById(unitId)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.assign(new AssignToUnitCommand(
                unitId, AssigneeType.STAFF, staffId, null, "admin-1")))
                .isInstanceOf(OrgUnitNotFoundException.class);
        verify(assignmentRepository, never()).endActive(any(), any(), any());
        verify(assignmentRepository, never()).save(any());
    }

    @Test
    void scopeOfUnit_queriesSubtreeByUnitPath() {
        given(unitRepository.findById(unitId)).willReturn(Optional.of(
                OrgUnit.create(UUID.randomUUID(), null, "MX", "México", "MX", null, "sys")));

        service.scopeOfUnit(unitId);

        verify(assignmentRepository).findActiveInSubtree("MX");
    }

    @Test
    void scopeOfUnit_unitNotFound_throws() {
        given(unitRepository.findById(unitId)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.scopeOfUnit(unitId))
                .isInstanceOf(OrgUnitNotFoundException.class);
    }

    @Test
    void endAssignment_delegatesAndReturnsCount() {
        given(assignmentRepository.endActive(eq(AssigneeType.STAFF), eq(staffId), any())).willReturn(1);

        assertThat(service.endAssignment(AssigneeType.STAFF, staffId)).isEqualTo(1);
    }
}

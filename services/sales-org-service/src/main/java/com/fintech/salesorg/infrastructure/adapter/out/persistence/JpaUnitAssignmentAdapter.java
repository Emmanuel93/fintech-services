package com.fintech.salesorg.infrastructure.adapter.out.persistence;

import com.fintech.salesorg.application.port.out.UnitAssignmentRepository;
import com.fintech.salesorg.domain.AssigneeType;
import com.fintech.salesorg.domain.UnitAssignment;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class JpaUnitAssignmentAdapter implements UnitAssignmentRepository {

    private final SpringDataUnitAssignmentRepository jpa;

    JpaUnitAssignmentAdapter(SpringDataUnitAssignmentRepository jpa) {
        this.jpa = jpa;
    }

    @Override public UnitAssignment save(UnitAssignment assignment) { return jpa.save(assignment); }

    @Override
    public int endActive(AssigneeType assigneeType, UUID assigneeId, Instant endedAt) {
        return jpa.endActive(assigneeType, assigneeId, endedAt);
    }

    @Override
    public Optional<UnitAssignment> findActiveByAssignee(AssigneeType assigneeType, UUID assigneeId) {
        return jpa.findByAssigneeTypeAndAssigneeIdAndEndedAtIsNull(assigneeType, assigneeId);
    }

    @Override
    public List<UnitAssignment> findActiveByUnit(UUID unitId) {
        return jpa.findByUnitIdAndEndedAtIsNull(unitId);
    }

    @Override
    public List<UnitAssignment> findActiveInSubtree(String ancestorPath) {
        return jpa.findActiveInSubtree(ancestorPath);
    }

    @Override
    public List<UnitAssignment> findHistoryByAssignee(AssigneeType assigneeType, UUID assigneeId) {
        return jpa.findByAssigneeTypeAndAssigneeIdOrderByAssignedAtDesc(assigneeType, assigneeId);
    }
}

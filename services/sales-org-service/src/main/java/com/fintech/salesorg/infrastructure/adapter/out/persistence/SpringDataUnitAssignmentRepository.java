package com.fintech.salesorg.infrastructure.adapter.out.persistence;

import com.fintech.salesorg.domain.AssigneeType;
import com.fintech.salesorg.domain.UnitAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface SpringDataUnitAssignmentRepository extends JpaRepository<UnitAssignment, UUID> {

    @Modifying
    @Query("UPDATE UnitAssignment a SET a.endedAt = :endedAt "
         + "WHERE a.assigneeType = :type AND a.assigneeId = :id AND a.endedAt IS NULL")
    int endActive(@Param("type") AssigneeType type, @Param("id") UUID id, @Param("endedAt") Instant endedAt);

    Optional<UnitAssignment> findByAssigneeTypeAndAssigneeIdAndEndedAtIsNull(AssigneeType type, UUID id);

    List<UnitAssignment> findByUnitIdAndEndedAtIsNull(UUID unitId);

    List<UnitAssignment> findByAssigneeTypeAndAssigneeIdOrderByAssignedAtDesc(AssigneeType type, UUID id);

    @Query(value = "SELECT a.* FROM sales_org.unit_assignments a "
                 + "JOIN sales_org.org_units u ON u.unit_id = a.unit_id "
                 + "WHERE a.ended_at IS NULL "
                 + "AND u.path::public.ltree <@ CAST(:ancestorPath AS public.ltree)",
            nativeQuery = true)
    List<UnitAssignment> findActiveInSubtree(@Param("ancestorPath") String ancestorPath);
}

package com.fintech.salesorg.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * La pertenencia de un asignado (empleado o distribuidor) a una unidad, <b>append-only</b>: para
 * reasignar no se actualiza esta fila, se cierra ({@link #end}) y se crea otra. La fila vigente
 * ({@code endedAt == null}) es "la unidad actual"; el resto es el historial.
 */
@Entity
@Table(name = "unit_assignments", schema = "sales_org")
public class UnitAssignment {

    @Id
    @Column(name = "assignment_id", nullable = false, updatable = false)
    private UUID assignmentId;

    @Column(name = "unit_id", nullable = false, updatable = false)
    private UUID unitId;

    @Enumerated(EnumType.STRING)
    @Column(name = "assignee_type", nullable = false, updatable = false, length = 20)
    private AssigneeType assigneeType;

    @Column(name = "assignee_id", nullable = false, updatable = false)
    private UUID assigneeId;

    @Column(name = "assignment_role", nullable = false, updatable = false, length = 40)
    private String assignmentRole;

    @Column(name = "assigned_by", length = 120, updatable = false)
    private String assignedBy;

    @Column(name = "assigned_at", nullable = false, updatable = false)
    private Instant assignedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    protected UnitAssignment() {}

    public static UnitAssignment create(UUID unitId, AssigneeType assigneeType, UUID assigneeId,
                                        String assignmentRole, String assignedBy) {
        UnitAssignment a = new UnitAssignment();
        a.assignmentId   = UUID.randomUUID();
        a.unitId         = unitId;
        a.assigneeType   = assigneeType;
        a.assigneeId     = assigneeId;
        a.assignmentRole = (assignmentRole == null || assignmentRole.isBlank()) ? "MEMBER" : assignmentRole;
        a.assignedBy     = assignedBy;
        a.assignedAt     = Instant.now();
        return a;
    }

    public void end(Instant when) {
        if (this.endedAt == null) {
            this.endedAt = when;
        }
    }

    public boolean isActive() { return endedAt == null; }

    public UUID getAssignmentId()    { return assignmentId; }
    public UUID getUnitId()          { return unitId; }
    public AssigneeType getAssigneeType() { return assigneeType; }
    public UUID getAssigneeId()      { return assigneeId; }
    public String getAssignmentRole(){ return assignmentRole; }
    public String getAssignedBy()    { return assignedBy; }
    public Instant getAssignedAt()   { return assignedAt; }
    public Instant getEndedAt()      { return endedAt; }
}

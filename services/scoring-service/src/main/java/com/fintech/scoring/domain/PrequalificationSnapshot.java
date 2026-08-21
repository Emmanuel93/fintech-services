package com.fintech.scoring.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "prequalification_snapshots", schema = "scoring")
public class PrequalificationSnapshot {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID snapshotId;

    @Column(nullable = false, updatable = false)
    private UUID prospectId;

    @Column(nullable = false, updatable = false, length = 20)
    private String prospectType;

    /** Null cuando aún no hay CirculoReport prefetcheado (prospecto recién registrado). */
    @Column
    private UUID reportId;

    /** Resultados por producto — mapeado a JSONB nativo por Hibernate (SqlTypes.JSON). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private List<PrequalificationItem> results;

    @Column(nullable = false)
    private Instant computedAt;

    protected PrequalificationSnapshot() {}

    public static PrequalificationSnapshot create(UUID prospectId, String prospectType,
                                                   UUID reportId, List<PrequalificationItem> results) {
        PrequalificationSnapshot s = new PrequalificationSnapshot();
        s.snapshotId   = UUID.randomUUID();
        s.prospectId   = prospectId;
        s.prospectType = prospectType;
        s.reportId     = reportId;
        s.results      = List.copyOf(results);
        s.computedAt   = Instant.now();
        return s;
    }

    /** Recalcula el snapshot existente (upsert lógico — misma fila, nuevo contenido). */
    public void refresh(UUID reportId, List<PrequalificationItem> results) {
        this.reportId   = reportId;
        this.results    = List.copyOf(results);
        this.computedAt = Instant.now();
    }

    public boolean isFresh(Duration ttl) {
        return computedAt.isAfter(Instant.now().minus(ttl));
    }

    public UUID getSnapshotId()                     { return snapshotId; }
    public UUID getProspectId()                     { return prospectId; }
    public String getProspectType()                 { return prospectType; }
    public UUID getReportId()                       { return reportId; }
    public List<PrequalificationItem> getResults()   { return results; }
    public Instant getComputedAt()                  { return computedAt; }
}

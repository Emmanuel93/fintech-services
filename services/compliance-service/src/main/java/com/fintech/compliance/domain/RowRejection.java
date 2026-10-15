package com.fintech.compliance.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/** Una fila que la carga no pudo usar, con lo necesario para arreglarla sin adivinar. */
@Entity
@Table(name = "watchlist_row_rejections", schema = "compliance")
public class RowRejection {

    @Id
    @Column(name = "rejection_id", nullable = false, updatable = false)
    private UUID rejectionId;

    @Column(name = "version_id", nullable = false, updatable = false)
    private UUID versionId;

    @Column(name = "source_line", nullable = false, updatable = false)
    private int sourceLine;

    @Column(name = "reason", length = 200, nullable = false, updatable = false)
    private String reason;

    /**
     * La línea cruda, recortada.
     *
     * <p>Se guarda porque sin ella el motivo no basta: «sin nombre» en la línea 4 417 no dice si
     * faltaba la columna, si venía vacía o si el separador estaba mal — y son tres arreglos
     * distintos.
     */
    @Column(name = "raw_line", length = 400, updatable = false)
    private String rawLine;

    protected RowRejection() { }

    public static RowRejection of(UUID versionId, int sourceLine, String reason, String rawLine) {
        RowRejection r = new RowRejection();
        r.rejectionId = UUID.randomUUID();
        r.versionId   = versionId;
        r.sourceLine  = sourceLine;
        r.reason      = cut(reason, 200);
        r.rawLine     = cut(rawLine, 400);
        return r;
    }

    private static String cut(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    public UUID getRejectionId() { return rejectionId; }
    public UUID getVersionId()   { return versionId; }
    public int getSourceLine()   { return sourceLine; }
    public String getReason()    { return reason; }
    public String getRawLine()   { return rawLine; }
}

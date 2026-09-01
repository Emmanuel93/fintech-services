package com.fintech.banking.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * El cruce entre lo que dice el banco y lo que dice la plataforma.
 *
 * <p><b>Guarda cómo se cruzó, y eso no es cosmético.</b> Una conciliación que no puede explicar por
 * qué cuadró dos importes no es auditable, y una heurística hay que poder revisarla <b>después</b>
 * de que alguien la aprobó — cuando ya nadie recuerda con qué criterio se hizo.
 */
@Entity
@Table(name = "bank_matches", schema = "banking")
public class BankMatch {

    /** Coincidió la clave de rastreo: no hay margen de error. */
    public static final String DETERMINISTA = "DETERMINISTIC";
    /** Coincidieron importe y fecha, o el importe y algo más. Lleva confianza. */
    public static final String HEURISTICO   = "HEURISTIC";
    /** Lo cruzó una persona. Lleva su nombre. */
    public static final String MANUAL       = "MANUAL";

    @Id
    @Column(name = "match_id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "line_id", nullable = false, updatable = false)
    private UUID lineId;

    @Column(name = "internal_type", nullable = false, length = 24)
    private String internalType;

    @Column(name = "internal_ref", nullable = false, length = 120)
    private String internalRef;

    @Column(name = "amount", nullable = false)
    private BigDecimal amount;

    @Column(name = "method", nullable = false, length = 16)
    private String method;

    /** De 0 a 1. Nula en un cruce determinista: no hay nada que estimar. */
    @Column(name = "confidence")
    private BigDecimal confidence;

    @Column(name = "matched_by", length = 80)
    private String matchedBy;

    @Column(name = "matched_at", nullable = false, updatable = false)
    private OffsetDateTime matchedAt;

    protected BankMatch() {}

    public static BankMatch determinista(UUID lineId, String tipo, String ref, BigDecimal monto) {
        return crear(lineId, tipo, ref, monto, DETERMINISTA, null, "SYSTEM");
    }

    public static BankMatch heuristico(UUID lineId, String tipo, String ref, BigDecimal monto,
                                       BigDecimal confianza) {
        if (confianza == null) {
            throw new IllegalArgumentException(
                    "Un cruce heurístico sin confianza no se puede revisar después");
        }
        return crear(lineId, tipo, ref, monto, HEURISTICO, confianza, "SYSTEM");
    }

    public static BankMatch manual(UUID lineId, String tipo, String ref, BigDecimal monto,
                                   String quien) {
        if (quien == null || quien.isBlank()) {
            throw new IllegalArgumentException("Un cruce manual sin autor no es auditable");
        }
        return crear(lineId, tipo, ref, monto, MANUAL, null, quien);
    }

    private static BankMatch crear(UUID lineId, String tipo, String ref, BigDecimal monto,
                                   String metodo, BigDecimal confianza, String quien) {
        BankMatch m = new BankMatch();
        m.id           = UUID.randomUUID();
        m.lineId       = lineId;
        m.internalType = tipo;
        m.internalRef  = ref;
        m.amount       = monto;
        m.method       = metodo;
        m.confidence   = confianza;
        m.matchedBy    = quien;
        m.matchedAt    = OffsetDateTime.now();
        return m;
    }

    public UUID getId()             { return id; }
    public UUID getLineId()         { return lineId; }
    public String getInternalType() { return internalType; }
    public String getInternalRef()  { return internalRef; }
    public BigDecimal getAmount()   { return amount; }
    public String getMethod()       { return method; }
    public BigDecimal getConfidence(){ return confidence; }
    public String getMatchedBy()    { return matchedBy; }
}

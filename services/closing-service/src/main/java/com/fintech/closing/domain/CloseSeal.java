package com.fintech.closing.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.UUID;

/**
 * El sello de una fase: el hecho auditable contra el que concilian contabilidad y bancos.
 *
 * <p>Las <b>cifras de control</b> no son adorno: son lo que hace posible el cuadre. Sin ellas el
 * sello sería una marca de tiempo sin poder probatorio, y la conciliación tendría que recalcular
 * la cartera entera cada vez que quisiera comparar.
 */
@Entity
@Table(name = "close_seals", schema = "closing")
public class CloseSeal {

    @Id
    @Column(name = "seal_id", nullable = false, updatable = false)
    private UUID sealId;

    @Column(name = "business_date", nullable = false) private LocalDate businessDate;
    @Column(name = "phase", nullable = false, length = 24) private String phase;
    @Column(name = "scope_key", nullable = false, length = 60) private String scopeKey;
    @Column(name = "unit_count", nullable = false) private int unitCount;

    @Column(name = "total_principal", nullable = false, precision = 19, scale = 4) private BigDecimal totalPrincipal;
    @Column(name = "total_interest", nullable = false, precision = 19, scale = 4)  private BigDecimal totalInterest;
    @Column(name = "total_penalty", nullable = false, precision = 19, scale = 4)   private BigDecimal totalPenalty;
    @Column(name = "total_debt", nullable = false, precision = 19, scale = 4)      private BigDecimal totalDebt;

    @Column(name = "content_hash", nullable = false, length = 64) private String contentHash;
    @Column(name = "sealed_at", nullable = false) private Instant sealedAt;

    protected CloseSeal() {}

    public static CloseSeal of(LocalDate businessDate, ClosePhase phase, String scopeKey,
                                int unitCount, BigDecimal principal, BigDecimal interest,
                                BigDecimal penalty, BigDecimal totalDebt) {
        CloseSeal s = new CloseSeal();
        s.sealId         = UUID.randomUUID();
        s.businessDate   = businessDate;
        s.phase          = phase.name();
        s.scopeKey       = scopeKey;
        s.unitCount      = unitCount;
        s.totalPrincipal = nz(principal);
        s.totalInterest  = nz(interest);
        s.totalPenalty   = nz(penalty);
        s.totalDebt      = nz(totalDebt);
        s.sealedAt       = Instant.now();
        s.contentHash    = s.computeHash();
        return s;
    }

    /** La escala de las columnas NUMERIC(19,4). Ver {@link #computeHash()}. */
    private static final int ESCALA_CANONICA = 4;

    /**
     * Huella del contenido sellado.
     *
     * <p>Permite detectar que un sello fue alterado después de emitido — que es distinto de que las
     * cifras hayan cambiado: lo segundo se ve comparando, lo primero no se vería sin esto.
     *
     * <p><b>Los importes se normalizan a la escala de la columna antes de resumirlos.</b> Sin eso,
     * la huella se calcula sobre {@code "20000"} al sellar y sobre {@code "20000.0000"} al releer
     * de la base —{@code NUMERIC(19,4)} devuelve siempre escala 4— y <b>todo sello leído parecería
     * alterado</b>. La detección de manipulación quedaría inservible justo cuando se usa: al
     * auditar un sello viejo.
     */
    private String computeHash() {
        String payload = String.join("|", businessDate.toString(), phase, scopeKey,
                String.valueOf(unitCount), canonico(totalPrincipal),
                canonico(totalInterest), canonico(totalPenalty), canonico(totalDebt));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }

    /** Si el contenido sigue correspondiendo a la huella emitida. */
    public boolean isIntact() { return contentHash.equals(computeHash()); }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }

    /** Misma cifra, misma cadena — venga de memoria o de la base. */
    private static String canonico(BigDecimal v) {
        return nz(v).setScale(ESCALA_CANONICA, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    public UUID getSealId()            { return sealId; }
    public LocalDate getBusinessDate() { return businessDate; }
    public ClosePhase phase()          { return ClosePhase.valueOf(phase); }
    public String getScopeKey()        { return scopeKey; }
    public int getUnitCount()          { return unitCount; }
    public BigDecimal getTotalPrincipal() { return totalPrincipal; }
    public BigDecimal getTotalInterest()  { return totalInterest; }
    public BigDecimal getTotalPenalty()   { return totalPenalty; }
    public BigDecimal getTotalDebt()      { return totalDebt; }
    public String getContentHash()        { return contentHash; }
    public Instant getSealedAt()          { return sealedAt; }
}

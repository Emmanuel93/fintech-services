package com.fintech.beneficiary.infrastructure.config;

import com.fintech.beneficiary.domain.IdentityVerificationMode;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

/**
 * Configuración del servicio: a quién le habla y con qué números trabaja.
 *
 * <p>Las URLs son de servicios de dominio, no del gateway: la composición es interna y no sale a
 * internet. Las que llevan un valor por defecto de {@code localhost} sirven para {@code bootRun};
 * en Docker las inyecta el compose.
 */
@ConfigurationProperties(prefix = "fintech.beneficiary")
public class BeneficiaryProperties {

    // ── Servicios que compone ────────────────────────────────────────────────────────────────

    private String creditPortfolioServiceUrl = "http://localhost:8087";
    private String creditProductServiceUrl   = "http://localhost:8084";
    private String scoringServiceUrl         = "http://localhost:8082";
    private String walletServiceUrl          = "http://localhost:8092";
    private String originationServiceUrl     = "http://localhost:8081";
    private String partyServiceUrl           = "http://localhost:8083";

    // ── Reglas de la liga ────────────────────────────────────────────────────────────────────

    private int inviteTtlDays = 7;
    private int guestSessionTtlMinutes = 15;
    private int maxInviteResends = 3;

    // ── Cotización de la colocación ──────────────────────────────────────────────────────────

    private BigDecimal minPlacementAmount = new BigDecimal("5000");

    /**
     * Tope por beneficiario cuando el catálogo no lo publica. El límite de verdad lo fija el
     * producto {@code DISTRIBUTOR_LINE}; esto es el respaldo para que un catálogo incompleto no
     * abra la puerta a colocarle la línea entera a una sola persona.
     */
    private BigDecimal maxPlacementAmount = new BigDecimal("60000");

    private BigDecimal amountStep = new BigDecimal("1000");

    /** Plazos permitidos, en quincenas. */
    private java.util.List<Integer> termFortnights = java.util.List.of(12, 24, 36, 48);

    /**
     * Tasa anual de la colocación cuando el producto no trae rate card. El pago quincenal se
     * calcula con ella sobre 24 periodos al año.
     */
    private BigDecimal defaultAnnualRate = new BigDecimal("0.289");

    /** Periodos al año. La colocación se cobra por quincena porque así cobra el distribuidor. */
    private int periodsPerYear = 24;

    /**
     * Comisión del distribuidor sobre lo que cobra a tiempo. Baja por día de atraso hasta cero al
     * quinto; la escalera completa vive en commission-service.
     */
    private BigDecimal commissionOnTimeRate = new BigDecimal("0.20");

    /**
     * Simular el KYC de la beneficiaria desde un endpoint interno. Es lo que sustituye a la web
     * pública mientras no exista: en un ambiente sin ese frontend, sin esto ninguna colocación
     * puede avanzar de {@code INVITED}. **Apagado fuera de local.**
     */
    private boolean kycSimulationEnabled = false;

    /** Cómo se verifica la identidad. Ver {@link IdentityVerification}. */
    private IdentityVerification identityVerification = new IdentityVerification();

    /**
     * La bandera del flujo de verificación.
     *
     * <p><b>Controla el flujo, no el arranque.</b> Los dos modos dan un servicio que funciona; lo
     * que cambia es si algo intenta resolver antes de llegar a una persona.
     */
    public static class IdentityVerification {

        /**
         * {@code MANUAL} (hoy) = todo lo revisa un analista, no se llama a nadie.
         * {@code AUTOMATIC} = el proveedor evalúa y el analista recibe sólo las excepciones.
         */
        private IdentityVerificationMode mode = IdentityVerificationMode.MANUAL;

        /**
         * Mínimos por métrica para dar por buena una identidad sin intervención humana.
         *
         * <p>Una métrica declarada aquí y **no reportada** por el proveedor cuenta como no
         * alcanzada: si se pidió medir la prueba de vida y no vino, no se sabe si pasó, y no
         * saberlo es exactamente el caso que va a una persona.
         *
         * <p>Los valores son de arranque y se calibran con los datos reales: por eso el veredicto
         * guarda qué umbral se incumplió, para poder mirarlos después con evidencia.
         */
        private java.util.Map<String, Double> thresholds = new java.util.LinkedHashMap<>(
                java.util.Map.of("facialMatch", 0.90, "liveness", 0.90, "documentAuthenticity", 0.85));

        public IdentityVerificationMode getMode() { return mode; }
        public void setMode(IdentityVerificationMode mode) { this.mode = mode; }
        public java.util.Map<String, Double> getThresholds() { return thresholds; }
        public void setThresholds(java.util.Map<String, Double> thresholds) { this.thresholds = thresholds; }
    }

    public IdentityVerification getIdentityVerification() { return identityVerification; }
    public void setIdentityVerification(IdentityVerification v) { this.identityVerification = v; }

    public String getCreditPortfolioServiceUrl() { return creditPortfolioServiceUrl; }
    public void setCreditPortfolioServiceUrl(String v) { this.creditPortfolioServiceUrl = v; }
    public String getCreditProductServiceUrl() { return creditProductServiceUrl; }
    public void setCreditProductServiceUrl(String v) { this.creditProductServiceUrl = v; }
    public String getScoringServiceUrl() { return scoringServiceUrl; }
    public void setScoringServiceUrl(String v) { this.scoringServiceUrl = v; }
    public String getWalletServiceUrl() { return walletServiceUrl; }
    public void setWalletServiceUrl(String v) { this.walletServiceUrl = v; }
    public String getOriginationServiceUrl() { return originationServiceUrl; }
    public void setOriginationServiceUrl(String v) { this.originationServiceUrl = v; }
    public String getPartyServiceUrl() { return partyServiceUrl; }
    public void setPartyServiceUrl(String v) { this.partyServiceUrl = v; }

    public int getInviteTtlDays() { return inviteTtlDays; }
    public void setInviteTtlDays(int v) { this.inviteTtlDays = v; }
    public int getGuestSessionTtlMinutes() { return guestSessionTtlMinutes; }
    public void setGuestSessionTtlMinutes(int v) { this.guestSessionTtlMinutes = v; }
    public int getMaxInviteResends() { return maxInviteResends; }
    public void setMaxInviteResends(int v) { this.maxInviteResends = v; }

    public BigDecimal getMinPlacementAmount() { return minPlacementAmount; }
    public void setMinPlacementAmount(BigDecimal v) { this.minPlacementAmount = v; }
    public BigDecimal getMaxPlacementAmount() { return maxPlacementAmount; }
    public void setMaxPlacementAmount(BigDecimal v) { this.maxPlacementAmount = v; }
    public BigDecimal getAmountStep() { return amountStep; }
    public void setAmountStep(BigDecimal v) { this.amountStep = v; }
    public java.util.List<Integer> getTermFortnights() { return termFortnights; }
    public void setTermFortnights(java.util.List<Integer> v) { this.termFortnights = v; }
    public BigDecimal getDefaultAnnualRate() { return defaultAnnualRate; }
    public void setDefaultAnnualRate(BigDecimal v) { this.defaultAnnualRate = v; }
    public int getPeriodsPerYear() { return periodsPerYear; }
    public void setPeriodsPerYear(int v) { this.periodsPerYear = v; }
    public BigDecimal getCommissionOnTimeRate() { return commissionOnTimeRate; }
    public void setCommissionOnTimeRate(BigDecimal v) { this.commissionOnTimeRate = v; }
    public boolean isKycSimulationEnabled() { return kycSimulationEnabled; }
    public void setKycSimulationEnabled(boolean v) { this.kycSimulationEnabled = v; }
}

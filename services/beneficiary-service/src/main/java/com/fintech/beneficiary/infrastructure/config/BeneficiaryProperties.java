package com.fintech.beneficiary.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.List;

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

    private BigDecimal minPlacementAmount = new BigDecimal("1000");

    /**
     * Tope por beneficiario cuando el catálogo no lo publica. El límite de verdad lo fija el
     * producto {@code DISTRIBUTOR_LINE}; esto es el respaldo para que un catálogo incompleto no
     * abra la puerta a colocarle la línea entera a una sola persona.
     */
    private BigDecimal maxPlacementAmount = new BigDecimal("10000");

    private BigDecimal amountStep = new BigDecimal("500");

    /** Plazos permitidos, en quincenas. */
    private List<Integer> termFortnights = List.of(12, 14, 16, 18, 20, 22, 24);

    /**
     * Tasa anual de la colocación cuando el producto no trae rate card. El pago quincenal se
     * calcula con ella sobre 24 periodos al año.
     */
    private BigDecimal defaultAnnualRate = new BigDecimal("0.95");

    /** Periodos al año. La colocación se cobra por quincena porque así cobra el distribuidor. */
    private int periodsPerYear = 24;

    /** The IVA on the placement's interest, inside the fortnightly payment as the portfolio's plan carries it. */
    private BigDecimal vatRate = new BigDecimal("0.16");

    /**
     * Comisión del distribuidor sobre lo que cobra a tiempo. Baja por día de atraso hasta cero al
     * quinto; la escalera completa vive en commission-service.
     */
    private BigDecimal commissionOnTimeRate = new BigDecimal("0.18");

    /**
     * Simular el KYC de la beneficiaria desde un endpoint interno. Es lo que sustituye a la web
     * pública mientras no exista: en un ambiente sin ese frontend, sin esto ninguna colocación
     * puede avanzar de {@code INVITED}. **Apagado fuera de local.**
     */
    private boolean kycSimulationEnabled = false;

    /** La web pública de KYC: liga, OTP y sus topes. Ver {@link PublicKyc}. */
    private PublicKyc publicKyc = new PublicKyc();

    /**
     * Las reglas de la superficie pública.
     *
     * <p>Los números salen del plan (§6.2) y no son técnicos sino de negocio: cuántas veces se le
     * puede mandar un código a una persona antes de que parezca acoso, y cuántos intentos se le
     * dan antes de sospechar que quien teclea no es ella.
     */
    public static class PublicKyc {

        /**
         * A dónde apunta la liga. La web lee el token de {@code ?t=}. En local es el Vite de la
         * página de KYC; en un ambiente real, el dominio público {@code kyc.*}.
         */
        private String linkBaseUrl = "http://localhost:4300/";

        /**
         * Código OTP fijo, <b>sólo para local</b>. Vacío = códigos aleatorios de verdad. Sin canal de
         * entrega real (WhatsApp/SMS son noop), es la única forma de recorrer el flujo a mano.
         */
        private String devOtpCode;

        /** Vigencia de un código. */
        private int otpTtlMinutes = 5;
        /** Envíos permitidos por ventana. */
        private int maxOtpSends = 3;
        /** La ventana de los envíos. */
        private int otpSendWindowMinutes = 15;
        /** Códigos equivocados antes de congelar la liga. */
        private int maxOtpFailures = 5;
        /** Cuánto dura el congelamiento. */
        private int freezeMinutes = 60;
        /** Lo que la web espera antes de ofrecer «reenviar código». Informativo: el tope real es maxOtpSends. */
        private int resendInSeconds = 45;

        public String getLinkBaseUrl() { return linkBaseUrl; }
        public void setLinkBaseUrl(String v) { this.linkBaseUrl = v; }
        public String getDevOtpCode() { return devOtpCode; }
        public void setDevOtpCode(String v) { this.devOtpCode = v; }
        public int getOtpTtlMinutes() { return otpTtlMinutes; }
        public void setOtpTtlMinutes(int v) { this.otpTtlMinutes = v; }
        public int getMaxOtpSends() { return maxOtpSends; }
        public void setMaxOtpSends(int v) { this.maxOtpSends = v; }
        public int getOtpSendWindowMinutes() { return otpSendWindowMinutes; }
        public void setOtpSendWindowMinutes(int v) { this.otpSendWindowMinutes = v; }
        public int getMaxOtpFailures() { return maxOtpFailures; }
        public void setMaxOtpFailures(int v) { this.maxOtpFailures = v; }
        public int getFreezeMinutes() { return freezeMinutes; }
        public void setFreezeMinutes(int v) { this.freezeMinutes = v; }
        public int getResendInSeconds() { return resendInSeconds; }
        public void setResendInSeconds(int v) { this.resendInSeconds = v; }

        /** Si hay código fijo configurado. Un valor en blanco cuenta como «no hay». */
        public boolean hasDevOtpCode() {
            return devOtpCode != null && !devOtpCode.isBlank();
        }
    }

    public PublicKyc getPublicKyc() { return publicKyc; }
    public void setPublicKyc(PublicKyc v) { this.publicKyc = v; }

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
    public List<Integer> getTermFortnights() { return termFortnights; }
    public void setTermFortnights(List<Integer> v) { this.termFortnights = v; }
    public BigDecimal getDefaultAnnualRate() { return defaultAnnualRate; }
    public void setDefaultAnnualRate(BigDecimal v) { this.defaultAnnualRate = v; }
    public int getPeriodsPerYear() { return periodsPerYear; }
    public void setPeriodsPerYear(int v) { this.periodsPerYear = v; }
    public BigDecimal getVatRate() { return vatRate; }
    public void setVatRate(BigDecimal v) { this.vatRate = v; }
    public BigDecimal getCommissionOnTimeRate() { return commissionOnTimeRate; }
    public void setCommissionOnTimeRate(BigDecimal v) { this.commissionOnTimeRate = v; }
    public boolean isKycSimulationEnabled() { return kycSimulationEnabled; }
    public void setKycSimulationEnabled(boolean v) { this.kycSimulationEnabled = v; }
}

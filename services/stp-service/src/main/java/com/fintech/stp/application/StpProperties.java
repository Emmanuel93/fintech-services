package com.fintech.stp.application;

import com.fintech.shared.banking.SpeiOperatingDay;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.time.LocalTime;
import java.util.Set;

@ConfigurationProperties(prefix = "fintech.stp")
@Validated
public class StpProperties {

    /**
     * Topic del que se leen las órdenes de pago. Configurable a propósito: es lo que permite que
     * este servicio se venda por separado sin adoptar nuestra nomenclatura.
     */
    @NotBlank
    private String inboundTopic = "disbursement.stp-requested";

    /** Base de la API de dispersión de STP. */
    @NotBlank
    private String baseUrl = "https://demo.stpmex.com:7024/speiws/rest/";

    /** Base de la API de consultas (saldo y conciliación). Es distinta de la de dispersión. */
    @NotBlank
    private String consultaBaseUrl = "https://efws-dev.stpmex.com/efws/API/";

    /** Salt del HMAC con el que se enmascara la cuenta del beneficiario antes de persistirla. */
    @NotBlank
    private String accountHashSalt = "cambiar-en-cada-ambiente";

    private final OperatingDay operatingDay = new OperatingDay();
    private final Collection collection = new Collection();
    private final Gateway gateway = new Gateway();
    private final Settlement settlement = new Settlement();
    private final Polling polling = new Polling();
    private final Outbox outbox = new Outbox();
    private final Http http = new Http();

    /**
     * The SPEI operation date (see {@link com.fintech.shared.banking.SpeiOperatingDay}): from the afternoon cutoff on,
     * or on a non-business day, a movement belongs to the next business day; outgoing orders registered within
     * {@code hold} of the cutoff wait until it has passed.
     */
    public static class OperatingDay {
        /** Mexico City time. */
        @NotNull
        private LocalTime cutoff = SpeiOperatingDay.DEFAULT_CUTOFF;
        @NotNull
        private Duration hold = SpeiOperatingDay.DEFAULT_HOLD;

        public LocalTime getCutoff() { return cutoff; }
        public void setCutoff(LocalTime cutoff) { this.cutoff = cutoff; }
        public Duration getHold() { return hold; }
        public void setHold(Duration hold) { this.hold = hold; }
    }

    /** Collection by CLABE: who issues the CLABEs, which credits are kept, how STP authenticates its webhook. */
    public static class Collection {
        /** The company whose STP range collection CLABEs are issued from. */
        @NotBlank
        private String companyCode = "KREDIUS";
        /** The connector's command topic: give a holder a collection CLABE. */
        @NotBlank
        private String requestTopic = "stp.collection-account-requested";
        /**
         * SPEI payment types kept; any other is returned with cause 15. The legacy connector ran on these against
         * STP in production.
         */
        @NotNull
        private Set<Integer> allowedPaymentTypes = Set.of(1, 5, 19, 20, 21, 22);
        /** Shared secret STP sends in {@code X-Webhook-Token}. Blank: not checked (local stack only). */
        private String webhookToken = "";

        public String getCompanyCode() { return companyCode; }
        public String getRequestTopic() { return requestTopic; }
        public void setRequestTopic(String requestTopic) { this.requestTopic = requestTopic; }
        public void setCompanyCode(String companyCode) { this.companyCode = companyCode; }
        public Set<Integer> getAllowedPaymentTypes() { return allowedPaymentTypes; }
        public void setAllowedPaymentTypes(Set<Integer> allowedPaymentTypes) { this.allowedPaymentTypes = allowedPaymentTypes; }
        public String getWebhookToken() { return webhookToken; }
        public void setWebhookToken(String webhookToken) { this.webhookToken = webhookToken; }
    }

    public static class Gateway {
        /** {@code real} habla con STP · {@code stub} usa el doble de ambientes bajos. */
        @NotBlank
        private String mode = "stub";
        private final Stub stub = new Stub();

        public String getMode() { return mode; }
        public void setMode(String mode) { this.mode = mode; }
        public Stub getStub() { return stub; }
        public boolean isStub() { return "stub".equalsIgnoreCase(mode); }
    }

    public static class Stub {
        /** Cuánto tarda una orden en aparecer liquidada en la conciliación simulada. */
        @NotNull
        private Duration settleAfter = Duration.ofSeconds(30);
        /** Latencia simulada por llamada, para que el timeout del cliente se ejercite. */
        @NotNull
        private Duration latency = Duration.ofMillis(200);
        /** Which amounts pick a scenario by their cents: every one, or only the ones a test armed. */
        @NotNull
        private ScenarioAmounts scenarioAmounts = ScenarioAmounts.ANY;

        public Duration getSettleAfter() { return settleAfter; }
        public void setSettleAfter(Duration settleAfter) { this.settleAfter = settleAfter; }
        public Duration getLatency() { return latency; }
        public void setLatency(Duration latency) { this.latency = latency; }
        public ScenarioAmounts getScenarioAmounts() { return scenarioAmounts; }
        public void setScenarioAmounts(ScenarioAmounts scenarioAmounts) { this.scenarioAmounts = scenarioAmounts; }
    }

    /**
     * Which amounts the stub reads a scenario from.
     *
     * <p>The connector's tests choose every amount, so any cents may pick a scenario. A whole platform also computes
     * amounts of its own: a commission lot, a funding payment. Eleven of the hundred cents are failures, so one such
     * payment in nine would time out, be cancelled or never settle, and no test wrote that.
     */
    public enum ScenarioAmounts {
        /** An amount's cents pick the scenario for every order. */
        ANY,
        /** Only an amount a test armed plays its cents' scenario; any other order is the happy path. */
        ARMED
    }

    /**
     * Qué hacer con el sello de la conciliación.
     *
     * <p>La cadena exacta que STP firma en la respuesta de conciliación no está confirmada contra su
     * especificación. Verificar contra una suposición y bloquear tendría un efecto peor que no
     * verificar: dinero que ya salió del banco y que este servicio nunca confirmaría.
     */
    public enum SignaturePolicy {
        /** El sello debe verificar. Si no, no se aplica el cambio de estado. Objetivo en producción. */
        ENFORCE,
        /** Se intenta verificar; si falla o no hay llave, se aplica y se marca SIGNATURE_UNVERIFIED. */
        WARN,
        /** No se verifica. Sólo para ambientes donde no hay llave de verificación en absoluto. */
        OFF
    }

    public static class Settlement {
        @NotNull
        private SignaturePolicy signaturePolicy = SignaturePolicy.WARN;

        public SignaturePolicy getSignaturePolicy() { return signaturePolicy; }
        public void setSignaturePolicy(SignaturePolicy signaturePolicy) { this.signaturePolicy = signaturePolicy; }
    }

    public static class Polling {
        private boolean enabled = true;
        /** Cada cuánto se consulta a STP mientras haya órdenes en vuelo. */
        @NotNull
        private Duration interval = Duration.ofMinutes(3);
        /** Gracia antes de consultar una orden recién enviada. */
        @NotNull
        private Duration grace = Duration.ofSeconds(30);
        /** SO-06: a partir de aquí se alerta. Nunca se marca SETTLED por timeout. */
        @NotNull
        private Duration settlementTimeout = Duration.ofHours(24);
        @Min(1)
        private int pageSize = 1000;
        @Min(1)
        private int maxPagesPerRun = 20;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public Duration getInterval() { return interval; }
        public void setInterval(Duration interval) { this.interval = interval; }
        public Duration getGrace() { return grace; }
        public void setGrace(Duration grace) { this.grace = grace; }
        public Duration getSettlementTimeout() { return settlementTimeout; }
        public void setSettlementTimeout(Duration settlementTimeout) { this.settlementTimeout = settlementTimeout; }
        public int getPageSize() { return pageSize; }
        public void setPageSize(int pageSize) { this.pageSize = pageSize; }
        public int getMaxPagesPerRun() { return maxPagesPerRun; }
        public void setMaxPagesPerRun(int maxPagesPerRun) { this.maxPagesPerRun = maxPagesPerRun; }
    }

    public static class Outbox {
        @NotNull
        private Duration relayInterval = Duration.ofSeconds(5);
        @Min(1)
        private int batchSize = 50;
        /** Tras esto la orden va a FAILED y a revisión manual. */
        @Min(1)
        private int maxAttempts = 6;
        /**
         * The wait before a message's first retry; each later one doubles it. The local stack sets zero: its stub
         * answers at once, so a transient rejection is retried, and exhausted, at the simulated moment the order was
         * registered instead of across the moves of the E2E's clock, by how long each move took (D63).
         */
        @NotNull
        private Duration baseBackoff = Duration.ofSeconds(5);

        public Duration getRelayInterval() { return relayInterval; }
        public void setRelayInterval(Duration relayInterval) { this.relayInterval = relayInterval; }
        public int getBatchSize() { return batchSize; }
        public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
        public int getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
        public Duration getBaseBackoff() { return baseBackoff; }
        public void setBaseBackoff(Duration baseBackoff) { this.baseBackoff = baseBackoff; }
    }

    public static class Http {
        @NotNull
        private Duration connectTimeout = Duration.ofSeconds(5);
        @NotNull
        private Duration readTimeout = Duration.ofSeconds(30);

        public Duration getConnectTimeout() { return connectTimeout; }
        public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }
        public Duration getReadTimeout() { return readTimeout; }
        public void setReadTimeout(Duration readTimeout) { this.readTimeout = readTimeout; }
    }

    public String getInboundTopic() { return inboundTopic; }
    public void setInboundTopic(String inboundTopic) { this.inboundTopic = inboundTopic; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getConsultaBaseUrl() { return consultaBaseUrl; }
    public void setConsultaBaseUrl(String consultaBaseUrl) { this.consultaBaseUrl = consultaBaseUrl; }
    public String getAccountHashSalt() { return accountHashSalt; }
    public void setAccountHashSalt(String accountHashSalt) { this.accountHashSalt = accountHashSalt; }
    public OperatingDay getOperatingDay() { return operatingDay; }
    public Collection getCollection() { return collection; }
    public Gateway getGateway() { return gateway; }
    public Settlement getSettlement() { return settlement; }
    public Polling getPolling() { return polling; }
    public Outbox getOutbox() { return outbox; }
    public Http getHttp() { return http; }
}

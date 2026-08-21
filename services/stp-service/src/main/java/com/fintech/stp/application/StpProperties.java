package com.fintech.stp.application;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

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

    private final Gateway gateway = new Gateway();
    private final Settlement settlement = new Settlement();
    private final Polling polling = new Polling();
    private final Outbox outbox = new Outbox();
    private final Http http = new Http();

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

        public Duration getSettleAfter() { return settleAfter; }
        public void setSettleAfter(Duration settleAfter) { this.settleAfter = settleAfter; }
        public Duration getLatency() { return latency; }
        public void setLatency(Duration latency) { this.latency = latency; }
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

        public Duration getRelayInterval() { return relayInterval; }
        public void setRelayInterval(Duration relayInterval) { this.relayInterval = relayInterval; }
        public int getBatchSize() { return batchSize; }
        public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
        public int getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
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
    public Gateway getGateway() { return gateway; }
    public Settlement getSettlement() { return settlement; }
    public Polling getPolling() { return polling; }
    public Outbox getOutbox() { return outbox; }
    public Http getHttp() { return http; }
}

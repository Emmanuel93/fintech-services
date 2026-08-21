package com.fintech.disbursement.infrastructure.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Nombres de topics. Vive en infraestructura a propósito: son detalle de transporte, y el núcleo no
 * debe poder nombrarlos.
 *
 * <p>Todos son configurables porque es lo que permite que un comprador adopte el servicio sin
 * adoptar nuestra nomenclatura de eventos (DC-6).
 */
@ConfigurationProperties(prefix = "fintech.disbursement.topics")
@Validated
public class DisbursementTopicProperties {

    /** Topic por proveedor. Clave = nombre del proveedor ({@code STP}, …). */
    private Map<String, String> provider = new LinkedHashMap<>();

    /** Entradas ACL. Vacío = el listener correspondiente no se registra. */
    private final Inbound inbound = new Inbound();

    @NotBlank
    private String accepted = "disbursement.accepted";
    @NotBlank
    private String completed = "disbursement.completed";
    @NotBlank
    private String failed = "disbursement.failed";
    @NotBlank
    private String returned = "disbursement.returned";

    /** Resultados que reportan los conectores. */
    private final ProviderOutcomes providerOutcomes = new ProviderOutcomes();

    public static class Inbound {
        private String creditAccountActivated = "credit-portfolio.credit-account-activated";
        private String dispositionAuthorized = "credit-portfolio.disposition-authorized";
        private String walletWithdrawalCompleted = "wallet.withdrawal-completed";

        public String getCreditAccountActivated() { return creditAccountActivated; }
        public void setCreditAccountActivated(String v) { this.creditAccountActivated = v; }
        public String getDispositionAuthorized() { return dispositionAuthorized; }
        public void setDispositionAuthorized(String v) { this.dispositionAuthorized = v; }
        public String getWalletWithdrawalCompleted() { return walletWithdrawalCompleted; }
        public void setWalletWithdrawalCompleted(String v) { this.walletWithdrawalCompleted = v; }
    }

    public static class ProviderOutcomes {
        private String accepted = "stp.order-accepted";
        private String settled  = "stp.order-settled";
        private String rejected = "stp.order-rejected";
        private String returned = "stp.order-returned";

        public String getAccepted() { return accepted; }
        public void setAccepted(String accepted) { this.accepted = accepted; }
        public String getSettled() { return settled; }
        public void setSettled(String settled) { this.settled = settled; }
        public String getRejected() { return rejected; }
        public void setRejected(String rejected) { this.rejected = rejected; }
        public String getReturned() { return returned; }
        public void setReturned(String returned) { this.returned = returned; }
    }

    public Map<String, String> getProvider() { return provider; }
    public void setProvider(Map<String, String> provider) { this.provider = provider; }
    public Inbound getInbound() { return inbound; }
    public ProviderOutcomes getProviderOutcomes() { return providerOutcomes; }
    public String getAccepted() { return accepted; }
    public void setAccepted(String accepted) { this.accepted = accepted; }
    public String getCompleted() { return completed; }
    public void setCompleted(String completed) { this.completed = completed; }
    public String getFailed() { return failed; }
    public void setFailed(String failed) { this.failed = failed; }
    public String getReturned() { return returned; }
    public void setReturned(String returned) { this.returned = returned; }
}

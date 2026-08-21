package com.fintech.stp.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Lo que STP nos dijo cuando le preguntamos. Evidencia cruda, append-only (SO-01).
 *
 * <p>Sustituye a los webhooks del legado: como la plataforma no expone nada a internet, la
 * información de liquidación llega porque el poller la va a buscar.
 *
 * <p>Inv: SO-02 {@code (companyId, trackingKey, observedStatus, observedAtSource)} es único. El
 * poller relee lo mismo cada N minutos por diseño; la segunda vez no reaplica nada.
 * Nota: {@code observedAtSource} es NOT NULL con default vacío justamente por esto — en Postgres
 * los NULL no colisionan en un UNIQUE, y una orden devuelta puede llegar sin {@code tsLiquidacion}.
 */
@Entity
@Table(schema = "stp", name = "settlement_observations")
public class SettlementObservation {

    @Id
    @Column(name = "observation_id", nullable = false, updatable = false)
    private UUID observationId;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "tracking_key", nullable = false, updatable = false)
    private String trackingKey;

    /** Código crudo de STP: LQ, TLQ, CCO, D, TD, RE, CL, TCL… */
    @Column(name = "observed_status", nullable = false, updatable = false)
    private String observedStatus;

    /** {@code tsLiquidacion} o {@code tsCaptura} tal como los devolvió STP. Nunca null. */
    @Column(name = "observed_at_source", nullable = false, updatable = false)
    private String observedAtSource;

    @Column(name = "return_cause_code")
    private String returnCauseCode;

    @Column(name = "cep_url")
    private String cepUrl;

    @Column(name = "cep_beneficiary_name")
    private String cepBeneficiaryName;

    @Column(name = "provider_signature")
    private String providerSignature;

    @Column(name = "signature_ok")
    private Boolean signatureOk;

    @Column(name = "raw_payload", nullable = false, updatable = false)
    private String rawPayload;

    @Column(name = "observed_via", nullable = false, updatable = false)
    private String observedVia;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "detail")
    private String detail;

    @Column(name = "observed_at", nullable = false, updatable = false)
    private Instant observedAt;

    protected SettlementObservation() {
    }

    public static SettlementObservation record(UUID companyId, String trackingKey, String observedStatus,
                                               String observedAtSource, String returnCauseCode,
                                               String cepUrl, String cepBeneficiaryName,
                                               String providerSignature, String rawPayload,
                                               ObservedVia observedVia) {
        SettlementObservation observation = new SettlementObservation();
        observation.observationId = UUID.randomUUID();
        observation.companyId = companyId;
        observation.trackingKey = trackingKey;
        observation.observedStatus = observedStatus;
        observation.observedAtSource = observedAtSource != null ? observedAtSource : "";
        observation.returnCauseCode = returnCauseCode;
        observation.cepUrl = cepUrl;
        observation.cepBeneficiaryName = cepBeneficiaryName;
        observation.providerSignature = providerSignature;
        observation.rawPayload = rawPayload;
        observation.observedVia = observedVia.name();
        observation.status = SettlementObservationStatus.APPLIED.name();
        observation.observedAt = Instant.now();
        return observation;
    }

    public void markApplied() { this.status = SettlementObservationStatus.APPLIED.name(); }

    public void markDuplicate() { this.status = SettlementObservationStatus.DUPLICATE.name(); }

    /** SO-03: no corresponde a ninguna orden nuestra. No se descarta, se alerta. */
    public void markUnmatched(String detail) {
        this.status = SettlementObservationStatus.UNMATCHED.name();
        this.detail = detail;
    }

    /** SO-04: el sello no verifica. No se aplica el cambio de estado. */
    public void markSignatureInvalid(String detail) {
        this.status = SettlementObservationStatus.SIGNATURE_INVALID.name();
        this.signatureOk = Boolean.FALSE;
        this.detail = detail;
    }

    public void markSignatureVerified() { this.signatureOk = Boolean.TRUE; }

    /** Se aplicó sin poder verificar el sello. Ver {@code SettlementObservationStatus}. */
    public void markSignatureUnverified(String detail) {
        this.status = SettlementObservationStatus.SIGNATURE_UNVERIFIED.name();
        this.signatureOk = Boolean.FALSE;
        this.detail = detail;
    }

    public void markFailed(String detail) {
        this.status = SettlementObservationStatus.FAILED.name();
        this.detail = detail;
    }

    public UUID getObservationId() { return observationId; }
    public UUID getCompanyId() { return companyId; }
    public String getTrackingKey() { return trackingKey; }
    public String getObservedStatus() { return observedStatus; }
    public String getObservedAtSource() { return observedAtSource; }
    public String getReturnCauseCode() { return returnCauseCode; }
    public String getCepUrl() { return cepUrl; }
    public String getCepBeneficiaryName() { return cepBeneficiaryName; }
    public String getProviderSignature() { return providerSignature; }
    public Boolean getSignatureOk() { return signatureOk; }
    public String getRawPayload() { return rawPayload; }
    public ObservedVia getObservedVia() { return ObservedVia.valueOf(observedVia); }
    public String getStatus() { return status; }
    public String getDetail() { return detail; }
    public Instant getObservedAt() { return observedAt; }
}

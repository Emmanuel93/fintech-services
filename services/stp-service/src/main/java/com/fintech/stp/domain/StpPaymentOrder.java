package com.fintech.stp.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Orden de pago SPEI registrada ante STP. Raíz de agregado.
 *
 * <p>Inv: SO-A1 estado terminal es inmutable.
 * <p>Inv: SO-A2 {@code paymentRequestId} es único — es la clave de idempotencia de entrada.
 * <p>Inv: SO-A3 {@code (companyId, trackingKey)} es único — la clave de rastreo es el identificador
 *              ante Banxico y no puede repetirse dentro de una empresa.
 * <p>Inv: SO-A4 se guardan el nombre completo del beneficiario (auditoría) y el truncado a 40 (lo
 *              que se firmó). El legado guardaba sólo el completo y firmaba el truncado, lo que
 *              rompía después la comparación contra el CEP.
 */
@Entity
@Table(schema = "stp", name = "payment_orders")
public class StpPaymentOrder {

    @Id
    @Column(name = "stp_payment_order_id", nullable = false, updatable = false)
    private UUID stpPaymentOrderId;

    @Column(name = "payment_request_id", nullable = false, updatable = false)
    private UUID paymentRequestId;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "ordering_account_id", nullable = false, updatable = false)
    private UUID orderingAccountId;

    @Column(name = "tracking_key", nullable = false, updatable = false)
    private String trackingKey;

    @Column(name = "business_date", nullable = false, updatable = false)
    private LocalDate businessDate;

    @Column(name = "amount", nullable = false, updatable = false)
    private BigDecimal amount;

    /** Completo, para auditoría y para comparar contra el CEP. */
    @Column(name = "beneficiary_name", nullable = false, updatable = false)
    private String beneficiaryName;

    /** Truncado a 40: exactamente lo que entró a la cadena firmada. */
    @Column(name = "beneficiary_name_sent", nullable = false, updatable = false)
    private String beneficiaryNameSent;

    @Column(name = "beneficiary_account", nullable = false, updatable = false)
    private String beneficiaryAccount;

    /** HMAC-SHA256 con salt. El legado usaba SHA-256 sin salt sobre un espacio enumerable. */
    @Column(name = "beneficiary_account_hash", nullable = false, updatable = false)
    private String beneficiaryAccountHash;

    @Column(name = "beneficiary_account_type", nullable = false, updatable = false)
    private String beneficiaryAccountType;

    @Column(name = "beneficiary_tax_id")
    private String beneficiaryTaxId;

    @Column(name = "beneficiary_institution", nullable = false, updatable = false)
    private Integer beneficiaryInstitution;

    @Column(name = "concept")
    private String concept;

    @Column(name = "numeric_reference")
    private Long numericReference;

    @Column(name = "payment_type")
    private String paymentType;

    @Column(name = "signature")
    private String signature;

    @Column(name = "signing_key_id")
    private UUID signingKeyId;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "stp_order_id")
    private String stpOrderId;

    @Column(name = "banxico_code")
    private Integer banxicoCode;

    @Column(name = "banxico_reason")
    private String banxicoReason;

    @Column(name = "error_detail")
    private String errorDetail;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "last_polled_at")
    private Instant lastPolledAt;

    @Column(name = "cep_url")
    private String cepUrl;

    @Column(name = "beneficiary_name_matches")
    private Boolean beneficiaryNameMatches;

    @Column(name = "correlation_id")
    private String correlationId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "settled_at")
    private Instant settledAt;

    protected StpPaymentOrder() {
    }

    public static StpPaymentOrder create(UUID paymentRequestId, UUID companyId, UUID orderingAccountId,
                                         TrackingKey trackingKey, LocalDate businessDate, BigDecimal amount,
                                         String beneficiaryName, String beneficiaryAccount,
                                         String beneficiaryAccountHash, String beneficiaryAccountType,
                                         String beneficiaryTaxId, Integer beneficiaryInstitution,
                                         String concept, Long numericReference, String paymentType,
                                         String correlationId) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("El monto de la orden debe ser positivo");
        }
        StpPaymentOrder order = new StpPaymentOrder();
        order.stpPaymentOrderId = UUID.randomUUID();
        order.paymentRequestId = paymentRequestId;
        order.companyId = companyId;
        order.orderingAccountId = orderingAccountId;
        order.trackingKey = trackingKey.value();
        order.businessDate = businessDate;
        order.amount = amount;
        order.beneficiaryName = beneficiaryName;
        order.beneficiaryNameSent = OrdenPagoFirmaSupport.truncate(beneficiaryName);
        order.beneficiaryAccount = beneficiaryAccount;
        order.beneficiaryAccountHash = beneficiaryAccountHash;
        order.beneficiaryAccountType = beneficiaryAccountType;
        order.beneficiaryTaxId = beneficiaryTaxId;
        order.beneficiaryInstitution = beneficiaryInstitution;
        order.concept = concept;
        order.numericReference = numericReference;
        order.paymentType = paymentType;
        order.status = StpPaymentOrderStatus.PENDING.name();
        order.attemptCount = 0;
        order.correlationId = correlationId;
        order.createdAt = Instant.now();
        return order;
    }

    /** Registra lo que se firmó y con qué llave, justo antes de salir a la red. */
    public void markSent(String signature, UUID signingKeyId) {
        requireNotTerminal("markSent");
        this.signature = signature;
        this.signingKeyId = signingKeyId;
        this.status = StpPaymentOrderStatus.SENT.name();
        this.attemptCount++;
        this.sentAt = Instant.now();
    }

    /** STP aceptó el registro. No significa que el dinero haya salido. */
    public void accept(String stpOrderId) {
        requireNotTerminal("accept");
        this.stpOrderId = stpOrderId;
        this.status = StpPaymentOrderStatus.ACCEPTED.name();
        this.banxicoCode = null;
        this.banxicoReason = null;
    }

    public void reject(BanxicoResponseCode code, String detail) {
        requireNotTerminal("reject");
        this.status = StpPaymentOrderStatus.REJECTED.name();
        this.banxicoCode = code.code();
        this.banxicoReason = code.name();
        this.errorDetail = detail;
    }

    /** Fallo transitorio: vuelve a PENDING para que el relay la reintente con backoff (DB-08). */
    public void scheduleRetry(BanxicoResponseCode code, String detail) {
        requireNotTerminal("scheduleRetry");
        this.status = StpPaymentOrderStatus.PENDING.name();
        this.banxicoCode = code.code();
        this.banxicoReason = code.name();
        this.errorDetail = detail;
    }

    /** No se pudo ni enviar: empresa sin resolver, sin llave vigente, CLABE inválida. */
    public void fail(String reason) {
        requireNotTerminal("fail");
        this.status = StpPaymentOrderStatus.FAILED.name();
        this.errorDetail = reason;
    }

    /** El dinero llegó. Único punto que fija {@code settledAt}. */
    public void settle(Instant settledAt, String cepUrl, Boolean beneficiaryNameMatches) {
        requireNotTerminal("settle");
        this.status = StpPaymentOrderStatus.SETTLED.name();
        this.settledAt = settledAt != null ? settledAt : Instant.now();
        this.cepUrl = cepUrl;
        this.beneficiaryNameMatches = beneficiaryNameMatches;
    }

    public void markReturned(String causeCode) {
        requireNotTerminal("markReturned");
        this.status = StpPaymentOrderStatus.RETURNED.name();
        this.errorDetail = causeCode;
    }

    public void markCancelled(String causeCode) {
        requireNotTerminal("markCancelled");
        this.status = StpPaymentOrderStatus.CANCELLED.name();
        this.errorDetail = causeCode;
    }

    public void markPolled(Instant moment) {
        this.lastPolledAt = moment;
    }

    public StpPaymentOrderStatus status() { return StpPaymentOrderStatus.valueOf(status); }
    public boolean isTerminal() { return status().isTerminal(); }
    public boolean isInFlight() { return status().isInFlight(); }

    private void requireNotTerminal(String operation) {
        if (isTerminal()) {
            throw new InvalidStpOrderStateException(
                    "No se puede ejecutar '" + operation + "' sobre una orden en estado terminal "
                            + status + " (paymentRequestId=" + paymentRequestId + ")");
        }
    }

    public UUID getStpPaymentOrderId() { return stpPaymentOrderId; }
    public UUID getPaymentRequestId() { return paymentRequestId; }
    public UUID getCompanyId() { return companyId; }
    public UUID getOrderingAccountId() { return orderingAccountId; }
    public String getTrackingKey() { return trackingKey; }
    public LocalDate getBusinessDate() { return businessDate; }
    public BigDecimal getAmount() { return amount; }
    public String getBeneficiaryName() { return beneficiaryName; }
    public String getBeneficiaryNameSent() { return beneficiaryNameSent; }
    public String getBeneficiaryAccount() { return beneficiaryAccount; }
    public String getBeneficiaryAccountHash() { return beneficiaryAccountHash; }
    public String getBeneficiaryAccountType() { return beneficiaryAccountType; }
    public String getBeneficiaryTaxId() { return beneficiaryTaxId; }
    public Integer getBeneficiaryInstitution() { return beneficiaryInstitution; }
    public String getConcept() { return concept; }
    public Long getNumericReference() { return numericReference; }
    public String getPaymentType() { return paymentType; }
    public String getSignature() { return signature; }
    public UUID getSigningKeyId() { return signingKeyId; }
    public String getStatus() { return status; }
    public String getStpOrderId() { return stpOrderId; }
    public Integer getBanxicoCode() { return banxicoCode; }
    public String getBanxicoReason() { return banxicoReason; }
    public String getErrorDetail() { return errorDetail; }
    public int getAttemptCount() { return attemptCount; }
    public Instant getLastPolledAt() { return lastPolledAt; }
    public String getCepUrl() { return cepUrl; }
    public Boolean getBeneficiaryNameMatches() { return beneficiaryNameMatches; }
    public String getCorrelationId() { return correlationId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getSentAt() { return sentAt; }
    public Instant getSettledAt() { return settledAt; }
}

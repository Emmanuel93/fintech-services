package com.fintech.disbursement.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.UUID;

/**
 * Contrato de entrada de los conectores, traducido.
 *
 * <p>Los nombres en Java son de pagos ({@code externalRef}, {@code receiptUrl}); los
 * {@code @JsonAlias} absorben cómo los llama cada proveedor. Así, si mañana entra otro conector con
 * {@code voucherUrl} en vez de {@code cepUrl}, se añade un alias y no se toca nada más — ni el
 * núcleo ni los otros adaptadores.
 */
public final class ProviderOutcomePayloads {

    private ProviderOutcomePayloads() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Accepted(
            @JsonAlias({"paymentRequestId"}) UUID disbursementId,
            @JsonAlias({"stpOrderId", "providerOrderId"}) String providerOrderId,
            @JsonAlias({"trackingKey"}) String externalRef,
            Instant occurredOn
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Settled(
            @JsonAlias({"paymentRequestId"}) UUID disbursementId,
            @JsonAlias({"trackingKey"}) String externalRef,
            @JsonAlias({"cepUrl", "voucherUrl"}) String receiptUrl,
            boolean beneficiaryNameMatches,
            Instant settledAt,
            Instant occurredOn
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Rejected(
            @JsonAlias({"paymentRequestId"}) UUID disbursementId,
            @JsonAlias({"banxicoCode", "providerCode"}) String providerCode,
            @JsonAlias({"banxicoReason", "providerReason"}) String providerReason,
            String detail,
            boolean retryable,
            Instant occurredOn
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Returned(
            @JsonAlias({"paymentRequestId"}) UUID disbursementId,
            @JsonAlias({"trackingKey"}) String externalRef,
            String returnCauseCode,
            Instant occurredOn
    ) {}
}

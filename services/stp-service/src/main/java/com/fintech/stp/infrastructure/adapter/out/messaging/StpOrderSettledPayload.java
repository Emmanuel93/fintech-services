package com.fintech.stp.infrastructure.adapter.out.messaging;

import java.time.Instant;
import java.util.UUID;

/** El dinero llegó. Único evento que lo afirma. */
record StpOrderSettledPayload(
        UUID paymentRequestId,
        UUID companyId,
        String trackingKey,
        String cepUrl,
        String cepBeneficiaryName,
        boolean beneficiaryNameMatches,
        Instant settledAt,
        String observedVia,
        Instant occurredOn
) {}

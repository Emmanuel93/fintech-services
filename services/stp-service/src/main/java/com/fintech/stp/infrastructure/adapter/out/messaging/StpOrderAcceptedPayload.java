package com.fintech.stp.infrastructure.adapter.out.messaging;

import java.time.Instant;
import java.util.UUID;

/** STP aceptó el registro. NO significa que el dinero haya salido. */
record StpOrderAcceptedPayload(
        UUID paymentRequestId,
        UUID companyId,
        String stpOrderId,
        String trackingKey,
        Instant occurredOn
) {}

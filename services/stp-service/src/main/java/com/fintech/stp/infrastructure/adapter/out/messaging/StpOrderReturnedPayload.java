package com.fintech.stp.infrastructure.adapter.out.messaging;

import java.time.Instant;
import java.util.UUID;

record StpOrderReturnedPayload(
        UUID paymentRequestId,
        UUID companyId,
        String trackingKey,
        String status,
        String returnCauseCode,
        String observedVia,
        Instant occurredOn
) {}

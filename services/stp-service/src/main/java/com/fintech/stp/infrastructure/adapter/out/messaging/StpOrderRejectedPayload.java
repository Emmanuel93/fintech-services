package com.fintech.stp.infrastructure.adapter.out.messaging;

import java.time.Instant;
import java.util.UUID;

record StpOrderRejectedPayload(
        UUID paymentRequestId,
        UUID companyId,
        Integer banxicoCode,
        String banxicoReason,
        String detail,
        boolean retryable,
        Instant occurredOn
) {}

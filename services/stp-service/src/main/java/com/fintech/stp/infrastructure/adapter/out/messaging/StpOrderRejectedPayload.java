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
        /*
         * Whether the order failed for a technical reason (the link, an order STP never registered) rather than a rule
         * of the bank or the beneficiary: the money did not leave, and a new request may succeed where this one could
         * not. The caller decides with it whether to ask again; a rule (PLD, an invalid account) is final.
         */
        boolean technical,
        Instant occurredOn
) {}

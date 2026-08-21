package com.fintech.collections.infrastructure.adapter.out.messaging;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Shared shape for PaymentPromiseMade / PaymentPromiseBroken. */
record PaymentPromiseEventPayload(
        UUID promiseId,
        UUID caseId,
        BigDecimal amount,
        LocalDate promisedDate,
        String recordedBy,
        Instant occurredOn
) {}

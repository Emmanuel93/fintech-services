package com.fintech.collections.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record CreatePaymentPromiseCommand(
        UUID caseId,
        BigDecimal amount,
        LocalDate promisedDate,
        String recordedBy
) {}

package com.fintech.origination.application;

import com.fintech.origination.domain.ApplicationStatus;
import com.fintech.origination.domain.ProductType;

import java.time.Instant;
import java.util.UUID;

public sealed interface StartCreditApplicationResult {

    record ApplicationStarted(
            UUID applicationId,
            UUID prospectId,
            ProductType productType,
            ApplicationStatus status,
            Instant createdAt
    ) implements StartCreditApplicationResult {}
}

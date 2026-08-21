package com.fintech.collections.infrastructure.adapter.out.messaging;

import com.fintech.collections.domain.RestructureTerms;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Shared shape for CollectionAgreementProposed / CollectionAgreementExecuted. */
record CollectionAgreementEventPayload(
        UUID agreementId,
        UUID caseId,
        UUID creditAccountId,
        UUID obligorPartyId,
        String type,
        BigDecimal forgivenAmount,
        RestructureTerms newTerms,
        String authorizedBy,
        String authorizationRef,
        Instant occurredOn
) {}

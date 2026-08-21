package com.fintech.collections.infrastructure.adapter.in.api.dto;

import com.fintech.collections.domain.CollectionAgreement;
import com.fintech.collections.domain.RestructureTerms;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record CollectionAgreementResponse(
        UUID agreementId,
        UUID caseId,
        UUID creditAccountId,
        String type,
        String status,
        BigDecimal originalDebt,
        BigDecimal forgivenAmount,
        RestructureTerms newTerms,
        String authorizedBy,
        String authorizationRef,
        Instant proposedAt,
        Instant respondedAt,
        Instant executedAt
) {
    public static CollectionAgreementResponse from(CollectionAgreement a) {
        return new CollectionAgreementResponse(a.getAgreementId(), a.getCaseId(), a.getCreditAccountId(),
                a.getType().name(), a.getStatus().name(), a.getOriginalDebt(), a.getForgivenAmount(),
                a.getNewTerms(), a.getAuthorizedBy(), a.getAuthorizationRef(),
                a.getProposedAt(), a.getRespondedAt(), a.getExecutedAt());
    }
}

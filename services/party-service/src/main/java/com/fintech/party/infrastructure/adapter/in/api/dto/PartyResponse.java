package com.fintech.party.infrastructure.adapter.in.api.dto;

import com.fintech.party.domain.Party;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record PartyResponse(
        UUID      partyId,
        UUID      prospectId,
        UUID      evaluationId,        // null until scoring completes
        String    partyType,
        String    status,
        String    firstName,
        String    lastName1,
        String    lastName2,
        String    curp,
        String    rfc,
        LocalDate dateOfBirth,
        String    riskLevel,           // null until scoring completes
        Integer   totalScore,          // null until scoring completes
        String    taxName,             // CFDI — null until fiscal profile captured
        String    taxRegime,
        String    taxZipCode,
        String    cfdiUse,
        UUID      assignedExecutiveId,   // ejecutivo de cuenta (backoffice) — null si sin asignar
        String    assignedExecutiveName,
        // ── Verificación de la persona (D16) ────────────────────────────────────────────────
        // Van en la ficha y no en un endpoint aparte porque son de la persona y quien la consulta
        // casi siempre necesita saber si puede operar con ella.
        String    identityStatus,
        String    screeningStatus,
        boolean   verificationClear,
        String    verificationBlockReason,
        Instant   createdAt
) {
    public static PartyResponse from(Party p) {
        return new PartyResponse(
                p.getPartyId(), p.getProspectId(), p.getEvaluationId(),
                p.getPartyType().name(), p.getStatus().name(),
                p.getFirstName(), p.getLastName1(), p.getLastName2(),
                p.getCurp(), p.getRfc(), p.getDateOfBirth(),
                p.getRiskLevel(), p.getTotalScore(),
                p.getTaxName(), p.getTaxRegime(), p.getTaxZipCode(), p.getCfdiUse(),
                p.getAssignedExecutiveId(), p.getAssignedExecutiveName(),
                p.getIdentityStatus(), p.getScreeningStatus(),
                p.isVerificationClear(), p.getVerificationBlockReason(),
                p.getCreatedAt()
        );
    }
}

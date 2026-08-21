package com.fintech.origination.infrastructure.adapter.in.api.dto;

import com.fintech.origination.domain.Contract;
import com.fintech.origination.domain.CreditApplication;
import com.fintech.origination.domain.CreditOffer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record CreditApplicationResponse(
        UUID applicationId,
        /**
         * Folio legible de la solicitud — {@code SOL-AAAAMM-XXXXXXXX}.
         *
         * <p>Es lo que el analista teclea y lo que el cliente dicta por teléfono;
         * nadie busca por UUID. Se deriva del id y de la fecha de alta con el
         * mismo patrón del número de contrato, así que es estable y no necesita
         * columna ni secuencia: el mismo registro produce siempre el mismo folio.
         */
        String folio,
        UUID prospectId,
        String prospectType,
        String productType,
        String status,
        BigDecimal requestedAmount,
        Integer requestedTerm,
        UUID scoreRequestId,
        String riskLevel,
        String decision,
        String rejectionReason,
        // Phase H — approval flow
        String approvalFlow,
        String decidedBy,
        Instant rejectedAt,
        // Phase E — offer
        String productCode,
        String productBehavior,
        BigDecimal offeredAmount,
        BigDecimal offeredLine,
        Integer offeredTerm,
        BigDecimal nominalRate,
        BigDecimal cat,
        Instant validUntil,
        Instant offerPresentedAt,
        Instant offerAcceptedAt,
        // Phase F — contract
        String contractNumber,
        String signatureMethod,
        String clabeAccount,
        String documentRef,
        Instant contractSignedAt,
        //
        Instant createdAt,
        Instant updatedAt
) {
    public static CreditApplicationResponse from(CreditApplication a) {
        CreditOffer o = a.getOffer();
        Contract c    = a.getContract();
        return new CreditApplicationResponse(
                a.getApplicationId(),
                folioOf(a),
                a.getProspectId(),
                a.getProspectType() != null ? a.getProspectType().name() : null,
                a.getProductType() != null ? a.getProductType().name() : null,
                a.getStatus() != null ? a.getStatus().name() : null,
                a.getRequestedAmount(),
                a.getRequestedTerm(),
                a.getScoreRequestId(),
                a.getRiskLevel(),
                a.getDecision(),
                a.getRejectionReason(),
                a.getApprovalFlow(),
                a.getDecidedBy(),
                a.getRejectedAt(),
                o != null ? o.getProductCode() : null,
                o != null ? o.getProductBehavior() : null,
                o != null ? o.getOfferedAmount() : null,
                o != null ? o.getOfferedLine() : null,
                o != null ? o.getOfferedTerm() : null,
                o != null ? o.getNominalRate() : null,
                o != null ? o.getCat() : null,
                o != null ? o.getValidUntil() : null,
                o != null ? o.getPresentedAt() : null,
                o != null ? o.getAcceptedAt() : null,
                c != null ? c.getContractNumber() : null,
                c != null ? c.getSignatureMethod() : null,
                c != null ? c.getClabeAccount() : null,
                c != null ? c.getDocumentRef() : null,
                c != null ? c.getSignedAt() : null,
                a.getCreatedAt(),
                a.getUpdatedAt());
    }

    /**
     * {@code SOL-AAAAMM-XXXXXXXX} a partir de la fecha de alta y el id.
     *
     * <p>Mismo patrón que el número de contrato para que los dos folios del
     * expediente se lean igual. Derivado, no almacenado: no hace falta columna,
     * migración ni secuencia, y el mismo registro siempre da el mismo folio.
     */
    private static String folioOf(CreditApplication a) {
        if (a.getApplicationId() == null) return null;
        java.time.Instant createdAt = a.getCreatedAt() != null ? a.getCreatedAt() : java.time.Instant.EPOCH;
        String yearMonth = java.time.LocalDateTime
                .ofInstant(createdAt, java.time.ZoneOffset.UTC)
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMM"));
        String suffix = a.getApplicationId().toString()
                .replace("-", "").substring(0, 8).toUpperCase();
        return "SOL-" + yearMonth + "-" + suffix;
    }
}

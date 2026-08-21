package com.fintech.commission.infrastructure.adapter.in.api.dto;

import com.fintech.commission.domain.CreditPromoterAssignment;

import java.util.UUID;

/** Un crédito atribuido a un distribuidor/promotor (para acotar la cartera por alcance). */
public record PromoterCreditResponse(
        UUID creditAccountId,
        UUID beneficiaryPartyId,
        String productType) {

    public static PromoterCreditResponse from(CreditPromoterAssignment a) {
        return new PromoterCreditResponse(a.getCreditAccountId(), a.getBeneficiaryPartyId(), a.getProductType());
    }
}

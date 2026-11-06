package com.fintech.commission.infrastructure.adapter.in.api.dto;

import com.fintech.commission.domain.BeneficiaryProfile;

import java.util.UUID;

/** A beneficiary as commission pays it: whether it withholds, and whether a lot can be paid. */
public record BeneficiaryProfileResponse(UUID partyId, String rfc, String taxRegime, boolean naturalPerson,
                                         String payoutClabe, String payoutHolder, boolean payable, String waitingFor) {

    public static BeneficiaryProfileResponse from(BeneficiaryProfile p) {
        return new BeneficiaryProfileResponse(p.getPartyId(), p.getRfc(), p.getTaxRegime(), p.isNaturalPerson(),
                p.getPayoutClabe(), p.getPayoutHolder(), p.isPayable(), p.whyNotPayable());
    }
}

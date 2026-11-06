package com.fintech.creditportfolio.application.port.out;

import com.fintech.creditportfolio.domain.beneficiary.BeneficiaryFlag;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The stored red-list marks (a distributor's reports), never deleted, only cleared. */
public interface BeneficiaryFlagRepository {

    BeneficiaryFlag save(BeneficiaryFlag flag);

    Optional<BeneficiaryFlag> findById(UUID flagId);

    List<BeneficiaryFlag> findActiveByBeneficiary(UUID beneficiaryPartyId);

    /** Every mark ever put on him, newest first. */
    List<BeneficiaryFlag> findByBeneficiary(UUID beneficiaryPartyId);
}

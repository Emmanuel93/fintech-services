package com.fintech.commission.application.port.out;

import com.fintech.commission.domain.BeneficiaryProfile;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BeneficiaryProfileRepository {

    Optional<BeneficiaryProfile> findById(UUID partyId);

    /** Every profile of those parties that has one; a party without a profile is just not in the list. */
    List<BeneficiaryProfile> findAllById(Iterable<UUID> partyIds);

    BeneficiaryProfile save(BeneficiaryProfile profile);
}

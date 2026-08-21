package com.fintech.commission.application.port.out;

import com.fintech.commission.domain.CreditPromoterAssignment;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CreditPromoterAssignmentRepository {
    Optional<CreditPromoterAssignment> findById(UUID creditAccountId);
    boolean existsById(UUID creditAccountId);
    CreditPromoterAssignment save(CreditPromoterAssignment assignment);

    /** Créditos atribuidos a un conjunto de distribuidores/promotores (por beneficiary_party_id). */
    List<CreditPromoterAssignment> findByBeneficiaryPartyIdIn(Collection<UUID> beneficiaryPartyIds);
}

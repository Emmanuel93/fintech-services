package com.fintech.creditportfolio.infrastructure.adapter.out.persistence;

import com.fintech.creditportfolio.domain.beneficiary.ClientPaymentReference;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface SpringDataClientReferenceRepository extends JpaRepository<ClientPaymentReference, Long> {

    Optional<ClientPaymentReference> findByCreditAccountIdAndBeneficiaryPartyId(UUID creditAccountId, UUID beneficiaryPartyId);

    List<ClientPaymentReference> findByCreditAccountIdOrderByAssignedAtAsc(UUID creditAccountId);

    /** One row per (line, client) placed and still without a reference: line, holder, client. */
    @Query(value = "SELECT DISTINCT d.credit_account_id, a.obligor_party_id, d.beneficiary_party_id "
                 + "  FROM credit_portfolio.dispositions d "
                 + "  JOIN credit_portfolio.credit_accounts a ON a.credit_account_id = d.credit_account_id "
                 + " WHERE d.beneficiary_party_id IS NOT NULL "
                 + "   AND d.disposition_type = 'THIRD_PARTY_CREDIT' "
                 + "   AND NOT EXISTS (SELECT 1 FROM credit_portfolio.client_payment_references r "
                 + "                    WHERE r.credit_account_id = d.credit_account_id "
                 + "                      AND r.beneficiary_party_id = d.beneficiary_party_id)",
            nativeQuery = true)
    List<Object[]> pairsWithoutReference();
}

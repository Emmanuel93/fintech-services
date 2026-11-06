package com.fintech.creditportfolio.infrastructure.adapter.out.persistence;

import com.fintech.creditportfolio.application.port.out.ClientReferenceRepository;
import com.fintech.creditportfolio.domain.beneficiary.ClientPaymentReference;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class JpaClientReferenceAdapter implements ClientReferenceRepository {

    private final SpringDataClientReferenceRepository jpa;

    JpaClientReferenceAdapter(SpringDataClientReferenceRepository jpa) {
        this.jpa = jpa;
    }

    @Override public ClientPaymentReference save(ClientPaymentReference r) { return jpa.save(r); }

    @Override
    public Optional<ClientPaymentReference> findByLineAndClient(UUID creditAccountId, UUID beneficiaryPartyId) {
        return jpa.findByCreditAccountIdAndBeneficiaryPartyId(creditAccountId, beneficiaryPartyId);
    }

    @Override
    public List<ClientPaymentReference> findByLine(UUID creditAccountId) {
        return jpa.findByCreditAccountIdOrderByAssignedAtAsc(creditAccountId);
    }

    @Override
    public Optional<ClientPaymentReference> findByReference(long paymentReference) {
        return jpa.findById(paymentReference);
    }

    @Override
    public List<Pair> pairsWithoutReference() {
        return jpa.pairsWithoutReference().stream()
                .map(r -> new Pair((UUID) r[0], (UUID) r[1], (UUID) r[2]))
                .toList();
    }
}

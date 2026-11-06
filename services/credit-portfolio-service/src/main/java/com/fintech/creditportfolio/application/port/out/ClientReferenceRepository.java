package com.fintech.creditportfolio.application.port.out;

import com.fintech.creditportfolio.domain.beneficiary.ClientPaymentReference;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The clients' payment references on the distributor lines. */
public interface ClientReferenceRepository {

    ClientPaymentReference save(ClientPaymentReference reference);

    Optional<ClientPaymentReference> findByLineAndClient(UUID creditAccountId, UUID beneficiaryPartyId);

    List<ClientPaymentReference> findByLine(UUID creditAccountId);

    Optional<ClientPaymentReference> findByReference(long paymentReference);

    /** The (line, client) pairs that have placements and no reference yet (placements made before references existed). */
    List<Pair> pairsWithoutReference();

    record Pair(UUID creditAccountId, UUID obligorPartyId, UUID beneficiaryPartyId) {}
}

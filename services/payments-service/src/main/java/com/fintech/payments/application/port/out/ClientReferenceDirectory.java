package com.fintech.payments.application.port.out;

import java.util.Optional;
import java.util.UUID;

/**
 * The payment references of the distributors' clients, as the portfolio assigns them: a deposit to a distributor's
 * CLABE with one of them is her client paying his own loans.
 */
public interface ClientReferenceDirectory {

    void record(long paymentReference, UUID creditAccountId, UUID obligorPartyId, UUID beneficiaryPartyId);

    Optional<ClientReference> find(long paymentReference);

    record ClientReference(long paymentReference, UUID creditAccountId, UUID obligorPartyId, UUID beneficiaryPartyId) {}
}

package com.fintech.payments.application.port.out;

import com.fintech.payments.domain.credit.CreditAllocation;
import com.fintech.payments.domain.credit.ReceivedCredit;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Received credits as payments applied them, with their allocation lines. */
public interface ReceivedCreditRepository {

    boolean exists(UUID inboundCreditId);

    ReceivedCredit save(ReceivedCredit credit);

    CreditAllocation save(CreditAllocation allocation);

    Optional<ReceivedCredit> findById(UUID inboundCreditId);

    List<ReceivedCredit> findByParty(UUID partyId);

    List<CreditAllocation> allocationsOf(UUID inboundCreditId);
}

package com.fintech.payments.infrastructure.adapter.out.persistence;

import com.fintech.payments.application.port.out.ReceivedCreditRepository;
import com.fintech.payments.domain.credit.CreditAllocation;
import com.fintech.payments.domain.credit.ReceivedCredit;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class JpaReceivedCreditRepository implements ReceivedCreditRepository {

    private final SpringDataReceivedCredits credits;
    private final SpringDataCreditAllocations allocations;

    JpaReceivedCreditRepository(SpringDataReceivedCredits credits, SpringDataCreditAllocations allocations) {
        this.credits = credits;
        this.allocations = allocations;
    }

    @Override public boolean exists(UUID inboundCreditId) { return credits.existsById(inboundCreditId); }
    @Override public ReceivedCredit save(ReceivedCredit credit) { return credits.save(credit); }
    @Override public CreditAllocation save(CreditAllocation allocation) { return allocations.save(allocation); }
    @Override public Optional<ReceivedCredit> findById(UUID inboundCreditId) { return credits.findById(inboundCreditId); }
    @Override public List<ReceivedCredit> findByParty(UUID partyId) { return credits.findByPartyIdOrderByReceivedAtAsc(partyId); }
    @Override public List<CreditAllocation> allocationsOf(UUID inboundCreditId) {
        return allocations.findByInboundCreditIdOrderBySeqAsc(inboundCreditId);
    }
}

package com.fintech.payments.infrastructure.adapter.out.persistence;

import com.fintech.payments.domain.credit.CreditAllocation;
import com.fintech.payments.domain.credit.ReceivedCredit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

interface SpringDataReceivedCredits extends JpaRepository<ReceivedCredit, UUID> {
    List<ReceivedCredit> findByPartyIdOrderByReceivedAtAsc(UUID partyId);
}

interface SpringDataCreditAllocations extends JpaRepository<CreditAllocation, UUID> {
    List<CreditAllocation> findByInboundCreditIdOrderBySeqAsc(UUID inboundCreditId);
}

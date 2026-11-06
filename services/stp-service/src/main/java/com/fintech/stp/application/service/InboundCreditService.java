package com.fintech.stp.application.service;

import com.fintech.stp.application.port.in.ReceiveInboundCreditUseCase;
import com.fintech.stp.application.port.out.CollectionAccountRepository;
import com.fintech.stp.application.port.out.CollectionEvents;
import com.fintech.stp.application.port.out.InboundCreditRepository;
import com.fintech.stp.application.port.out.IntegrationEventOutbox;
import com.fintech.stp.domain.collection.CollectionAccount;
import com.fintech.stp.domain.collection.CreditAcceptancePolicy;
import com.fintech.stp.domain.collection.InboundCredit;
import com.fintech.stp.domain.collection.InboundCreditFacts;
import com.fintech.stp.domain.collection.ReturnCause;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * The inbox of SPEI credits. STP announces a credit and waits: the credit is recorded with its decision and, in the
 * same transaction, its announcement is written to the outbox ({@code stp.credit-received}). Nothing leaves before
 * the decision is stored, and nothing is decided twice: a credit already seen gets the answer it got the first time.
 */
@Service
public class InboundCreditService implements ReceiveInboundCreditUseCase {

    private static final Logger log = LoggerFactory.getLogger(InboundCreditService.class);

    private final InboundCreditRepository credits;
    private final CollectionAccountRepository accounts;
    private final CreditAcceptancePolicy policy;
    private final IntegrationEventOutbox outbox;

    public InboundCreditService(InboundCreditRepository credits, CollectionAccountRepository accounts,
                                CreditAcceptancePolicy policy, IntegrationEventOutbox outbox) {
        this.credits = credits;
        this.accounts = accounts;
        this.policy = policy;
        this.outbox = outbox;
    }

    @Override
    @Transactional
    public CreditReceipt receive(InboundCreditFacts facts) {
        Optional<InboundCredit> seen = credits.findByStpId(facts.companyId(), facts.stpId())
                .or(() -> facts.trackingKey() == null || facts.operationDate() == null ? Optional.empty()
                        : credits.findByTrackingKey(facts.companyId(), facts.trackingKey(), facts.operationDate()));
        if (seen.isPresent()) {
            log.info("Abono {} ya recibido: se contesta lo mismo ({})", facts.trackingKey(), seen.get().decision());
            return CreditReceipt.of(seen.get(), true);
        }

        Optional<CollectionAccount> account = Optional.ofNullable(facts.beneficiaryClabe())
                .flatMap(accounts::findByClabe)
                .filter(a -> a.getCompanyId().equals(facts.companyId()));
        Optional<ReturnCause> cause = policy.evaluate(facts, account);
        InboundCredit credit = cause
                .map(c -> InboundCredit.returned(facts, account.orElse(null), c))
                .orElseGet(() -> InboundCredit.confirmed(facts, account.orElseThrow()));
        credits.save(credit);

        String key = credit.getPartyId() != null ? credit.getPartyId().toString() : credit.getBeneficiaryClabe();
        outbox.enqueue(CollectionEvents.TOPIC_CREDIT_RECEIVED, key, credit.getInboundCreditId(),
                CollectionEvents.CreditReceived.of(credit));

        if (credit.isConfirmed()) {
            log.info("Abono confirmado clave={} importe={} operación={} party={}", credit.getTrackingKey(),
                    credit.getAmount(), credit.getOperationDate(), credit.getPartyId());
        } else {
            log.warn("Abono devuelto clave={} importe={} causa={} ({})", credit.getTrackingKey(), credit.getAmount(),
                    credit.getReturnCause(), credit.getReturnReason());
        }
        return CreditReceipt.of(credit, false);
    }
}

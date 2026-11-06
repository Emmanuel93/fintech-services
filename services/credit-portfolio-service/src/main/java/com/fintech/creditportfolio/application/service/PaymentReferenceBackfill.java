package com.fintech.creditportfolio.application.service;

import com.fintech.creditportfolio.application.port.out.CreditAccountRepository;
import com.fintech.creditportfolio.application.port.out.PaymentReferenceSequence;
import com.fintech.creditportfolio.domain.CreditAccount;
import com.fintech.creditportfolio.domain.PaymentReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * Gives a payment reference to every loan opened before references existed, once, at startup. Idempotent: a loan that
 * has one keeps it, and a fresh environment has nothing to do.
 */
@Component
public class PaymentReferenceBackfill {

    private static final Logger log = LoggerFactory.getLogger(PaymentReferenceBackfill.class);

    private final CreditAccountRepository accounts;
    private final PaymentReferenceSequence references;
    private final TransactionTemplate transaction;

    public PaymentReferenceBackfill(CreditAccountRepository accounts, PaymentReferenceSequence references,
                                    TransactionTemplate transaction) {
        this.accounts = accounts;
        this.references = references;
        this.transaction = transaction;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void assignMissing() {
        Integer assigned = transaction.execute(status -> {
            List<CreditAccount> pending = accounts.findWithoutPaymentReference();
            for (CreditAccount account : pending) {
                account.assignPaymentReference(PaymentReference.of(references.nextBody()));
                accounts.save(account);
            }
            return pending.size();
        });
        if (assigned != null && assigned > 0) {
            log.info("Referencia de pago asignada a {} créditos anteriores", assigned);
        }
    }
}

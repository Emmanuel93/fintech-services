package com.fintech.creditportfolio.application.service;

import com.fintech.creditportfolio.application.port.out.ClientReferenceRepository;
import com.fintech.creditportfolio.application.port.out.CreditPortfolioEventPublisher;
import com.fintech.creditportfolio.application.port.out.PaymentReferenceSequence;
import com.fintech.creditportfolio.domain.PaymentReference;
import com.fintech.creditportfolio.domain.beneficiary.ClientPaymentReference;
import com.fintech.creditportfolio.domain.event.ClientReferenceAssignedEvent;
import com.fintech.shared.time.BusinessClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * The client's payment reference on a distributor's line: assigned the first time she places him a loan, the same for
 * every later one, and published so payments can recognize a deposit made with it.
 */
@Service
@Transactional
public class ClientReferenceService {

    private static final Logger log = LoggerFactory.getLogger(ClientReferenceService.class);

    private final ClientReferenceRepository references;
    private final PaymentReferenceSequence sequence;
    private final CreditPortfolioEventPublisher publisher;

    public ClientReferenceService(ClientReferenceRepository references, PaymentReferenceSequence sequence,
                                  CreditPortfolioEventPublisher publisher) {
        this.references = references;
        this.sequence = sequence;
        this.publisher = publisher;
    }

    /** His reference on her line, assigned now if he has none. */
    public ClientPaymentReference ensure(UUID creditAccountId, UUID obligorPartyId, UUID beneficiaryPartyId) {
        Optional<ClientPaymentReference> existing = references.findByLineAndClient(creditAccountId, beneficiaryPartyId);
        if (existing.isPresent()) return existing.get();
        ClientPaymentReference assigned = references.save(ClientPaymentReference.of(PaymentReference.of(sequence.nextBody()),
                creditAccountId, obligorPartyId, beneficiaryPartyId, BusinessClock.current().now()));
        publisher.publishClientReferenceAssigned(new ClientReferenceAssignedEvent(creditAccountId, obligorPartyId,
                beneficiaryPartyId, assigned.getPaymentReference()));
        log.info("Referencia de pago {} asignada al cliente {} en la línea {}", assigned.getPaymentReference(),
                beneficiaryPartyId, creditAccountId);
        return assigned;
    }

    @Transactional(readOnly = true)
    public Optional<ClientPaymentReference> find(long reference) {
        return references.findByReference(reference);
    }
}

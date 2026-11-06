package com.fintech.accounting.infrastructure.adapter.in.messaging;

import com.fintech.accounting.domain.PaymentOrigin;
import com.fintech.accounting.application.service.PostingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class RecoveryPaymentAppliedListener {

    private static final Logger log = LoggerFactory.getLogger(RecoveryPaymentAppliedListener.class);
    private final PostingService postingService;

    public RecoveryPaymentAppliedListener(PostingService postingService) {
        this.postingService = postingService;
    }

    @KafkaListener(topics = "collections.recovery-payment-applied",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "recoveryPaymentAppliedListenerContainerFactory")
    public void onMessage(RecoveryPaymentAppliedPayload p) {
        log.debug("recovery-payment-applied creditAccountId={} amount={}", p.creditAccountId(), p.recoveredAmount());
        // One voucher per recovery: keyed by the write-off alone, only the first recovery was ever booked.
        String clave = "RECOVERY-" + (p.paymentId() != null ? p.paymentId() : p.writeOffId());
        // The method of a recovery is where its money came from (payments' funding source).
        postingService.onRecoveryPayment(clave, p.creditAccountId(), null, p.recoveredAmount(), p.occurredOn(),
                new PaymentOrigin(p.paymentMethod(), p.inboundCreditId()));
    }
}

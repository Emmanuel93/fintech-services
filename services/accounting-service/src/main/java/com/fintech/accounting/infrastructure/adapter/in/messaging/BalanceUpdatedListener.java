package com.fintech.accounting.infrastructure.adapter.in.messaging;

import com.fintech.accounting.application.service.PostingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class BalanceUpdatedListener {

    private static final Logger log = LoggerFactory.getLogger(BalanceUpdatedListener.class);
    private final PostingService postingService;

    public BalanceUpdatedListener(PostingService postingService) {
        this.postingService = postingService;
    }

    @KafkaListener(topics = "credit-portfolio.balance-updated",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "balanceUpdatedListenerContainerFactory")
    public void onMessage(BalanceUpdatedPayload p) {
        log.debug("balance-updated creditAccountId={} trigger={} v={}", p.creditAccountId(), p.triggerEvent(), p.balanceVersion());
        postingService.onBalanceUpdated(p.eventId(), p.creditAccountId(), p.obligorPartyId(),
                nz(p.principalBalance()), nz(p.accruedInterestBalance()), nz(p.penaltyBalance()),
                nz(p.totalDebt()), p.triggerEvent(), p.balanceVersion(),
                p.originUnitCode(), p.occurredOn(), p.eventAmount(),
                p.principalDelta(), p.interestDelta(), p.penaltyDelta());
    }

    private static java.math.BigDecimal nz(java.math.BigDecimal v) {
        return v != null ? v : java.math.BigDecimal.ZERO;
    }
}

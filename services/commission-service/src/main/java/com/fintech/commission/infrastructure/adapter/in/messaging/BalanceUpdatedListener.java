package com.fintech.commission.infrastructure.adapter.in.messaging;

import com.fintech.commission.application.service.CommissionAccrualService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class BalanceUpdatedListener {

    private static final Logger log = LoggerFactory.getLogger(BalanceUpdatedListener.class);
    private final CommissionAccrualService accrualService;

    public BalanceUpdatedListener(CommissionAccrualService accrualService) {
        this.accrualService = accrualService;
    }

    @KafkaListener(topics = "credit-portfolio.balance-updated",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "balanceUpdatedListenerContainerFactory")
    public void onMessage(BalanceUpdatedPayload p) {
        log.debug("balance-updated creditAccountId={} trigger={} interest={}",
                p.creditAccountId(), p.triggerEvent(), p.accruedInterestBalance());
        accrualService.onBalanceUpdated(p.eventId(), p.creditAccountId(),
                p.accruedInterestBalance() != null ? p.accruedInterestBalance() : java.math.BigDecimal.ZERO,
                p.triggerEvent(), p.balanceVersion());
    }
}

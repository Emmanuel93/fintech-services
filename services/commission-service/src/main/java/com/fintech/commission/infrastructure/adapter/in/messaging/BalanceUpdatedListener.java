package com.fintech.commission.infrastructure.adapter.in.messaging;

import com.fintech.commission.application.BalanceMove;
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
        log.debug("balance-updated creditAccountId={} trigger={} interest={} interestDelta={}",
                p.creditAccountId(), p.triggerEvent(), p.accruedInterestBalance(), p.interestDelta());
        BalanceUpdatedPayload.NettingPayload n = p.commission();
        accrualService.onBalanceUpdated(new BalanceMove(p.eventId(), p.creditAccountId(), p.triggerEvent(),
                p.balanceVersion(), p.accruedInterestBalance(), p.interestDelta(), p.sourceEventId(), p.valueDate(),
                p.commissionSettlement(), n == null ? null : new BalanceMove.Netting(n.cycleNumber(), n.paymentDueDate(),
                        n.daysLate(), n.rate(), n.onTimeRate(), n.basis(), n.amount(), n.paymentOrder())));
    }
}

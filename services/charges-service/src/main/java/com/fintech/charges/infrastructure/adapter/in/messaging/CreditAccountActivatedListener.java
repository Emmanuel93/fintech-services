package com.fintech.charges.infrastructure.adapter.in.messaging;

import com.fintech.charges.application.service.AccrualScheduleService;
import com.fintech.charges.application.service.BalanceSnapshotService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class CreditAccountActivatedListener {

    private static final Logger log = LoggerFactory.getLogger(CreditAccountActivatedListener.class);

    private final AccrualScheduleService scheduleService;
    private final BalanceSnapshotService snapshotService;

    public CreditAccountActivatedListener(AccrualScheduleService scheduleService,
                                           BalanceSnapshotService snapshotService) {
        this.scheduleService = scheduleService;
        this.snapshotService = snapshotService;
    }

    @KafkaListener(
            topics = "credit-portfolio.credit-account-activated",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "creditAccountActivatedListenerContainerFactory")
    public void onCreditAccountActivated(CreditAccountActivatedPayload event) {
        log.info("credit-account-activated received creditAccountId={} productType={} behavior={}",
                event.creditAccountId(), event.productType(), event.productBehavior());

        scheduleService.createFromActivation(
                event.creditAccountId(),
                event.obligorPartyId(),
                event.productType(),
                event.productBehavior(),
                event.nominalRate(),
                event.moratoriumRate(),
                event.principalBalance(),
                event.openingFeeRate(),
                // BNPL: nulo = devenga desde el alta, que es lo que son todos los créditos hoy.
                event.accrualStartDate());

        snapshotService.initSnapshot(
                event.creditAccountId(),
                event.obligorPartyId(),
                event.creditLimit());
    }
}

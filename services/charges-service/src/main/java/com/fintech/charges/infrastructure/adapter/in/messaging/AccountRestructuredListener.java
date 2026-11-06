package com.fintech.charges.infrastructure.adapter.in.messaging;

import com.fintech.charges.application.service.AccrualScheduleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** A restructure took effect in the portfolio: the account accrues the agreed rate from now on. */
@Component
public class AccountRestructuredListener {

    private static final Logger log = LoggerFactory.getLogger(AccountRestructuredListener.class);

    private final AccrualScheduleService scheduleService;

    public AccountRestructuredListener(AccrualScheduleService scheduleService) {
        this.scheduleService = scheduleService;
    }

    @KafkaListener(
            topics = "credit-portfolio.account-restructured",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "accountRestructuredListenerContainerFactory")
    public void onRestructured(AccountRestructuredPayload event) {
        log.info("account-restructured recibido creditAccountId={} tasa {} → {} desde {}", event.creditAccountId(),
                event.previousNominalRate(), event.newNominalRate(), event.effectiveDate());
        scheduleService.applyRestructure(event.creditAccountId(), event.newNominalRate());
    }
}

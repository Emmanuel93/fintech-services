package com.fintech.charges.infrastructure.adapter.in.messaging;

import com.fintech.charges.application.service.AccrualScheduleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * BK-18 · el listener que faltaba para que la mora existiera.
 *
 * <p>Cartera medía la mora y publicaba el hecho desde siempre. Este servicio consumía
 * {@code balance-updated}, {@code charge-rejected} y {@code credit-account-activated} — y no la
 * mora. El resultado: {@code activateMoratorium()} sin un solo llamador de producción, y la cuenta
 * {@code 4102} sin un abono en toda la historia del sistema.
 */
@Component
public class DelinquencyStatusUpdatedListener {

    private static final Logger log = LoggerFactory.getLogger(DelinquencyStatusUpdatedListener.class);

    private final AccrualScheduleService scheduleService;

    public DelinquencyStatusUpdatedListener(AccrualScheduleService scheduleService) {
        this.scheduleService = scheduleService;
    }

    @KafkaListener(
            topics = "credit-portfolio.delinquency-status-updated",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "delinquencyStatusUpdatedListenerContainerFactory")
    public void onDelinquencyUpdated(DelinquencyStatusUpdatedPayload event) {
        log.debug("delinquency-status-updated recibido creditAccountId={} dpd={} capitalVencido={}",
                event.creditAccountId(), event.daysDelinquent(), event.overduePrincipal());

        scheduleService.actualizarMora(
                event.creditAccountId(), event.overduePrincipal(), event.oldestDueDate());
    }
}

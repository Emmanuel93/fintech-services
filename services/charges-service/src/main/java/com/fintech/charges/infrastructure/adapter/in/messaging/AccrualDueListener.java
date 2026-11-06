package com.fintech.charges.infrastructure.adapter.in.messaging;

import com.fintech.charges.application.service.dayclose.AccrualUnitExecutor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * El trabajador del cierre: cada pod del grupo devenga las cuentas de sus particiones.
 *
 * <p>El paralelismo total es {@code min(particiones, réplicas × concurrency)}. Con 12 particiones y 4 hilos
 * por pod, el tercer pod llena el techo; un cuarto sólo sirve si se suben particiones.
 */
@Component
class AccrualDueListener {

    private final AccrualUnitExecutor executor;

    AccrualDueListener(AccrualUnitExecutor executor) {
        this.executor = executor;
    }

    @KafkaListener(
            topics = "charges.accrual-due",
            groupId = "${fintech.day-close.units.consumer-group:charges-accrual-workers}",
            concurrency = "${fintech.day-close.units.concurrency:4}",
            containerFactory = "accrualDueListenerContainerFactory")
    void onAccrualDue(AccrualDueMessage message) {
        if (message == null || message.businessDate() == null || message.creditAccountId() == null) return;
        executor.execute(message.businessDate(), message.creditAccountId());
    }
}

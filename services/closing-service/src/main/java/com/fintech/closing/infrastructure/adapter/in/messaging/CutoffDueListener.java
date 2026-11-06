package com.fintech.closing.infrastructure.adapter.in.messaging;

import com.fintech.closing.application.service.cutoff.CutoffUnitExecutor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * El trabajador del corte: cada pod del grupo corta las cuentas de sus particiones.
 *
 * <p>El paralelismo total es {@code min(particiones, réplicas × concurrency)}. Con 12 particiones y
 * 4 hilos por pod, el tercer pod ya llena el techo; un cuarto sólo sirve si se suben particiones.
 */
@Component
public class CutoffDueListener {

    private final CutoffUnitExecutor executor;

    public CutoffDueListener(CutoffUnitExecutor executor) {
        this.executor = executor;
    }

    @KafkaListener(
            topics = "closing.cutoff-due",
            groupId = "${fintech.closing.cutoff.consumer-group:closing-cutoff-workers}",
            concurrency = "${fintech.closing.cutoff.concurrency:4}",
            containerFactory = "cutoffDueListenerContainerFactory")
    public void onCutoffDue(CutoffDueMessage message) {
        if (message == null || message.unitId() == null || message.businessDate() == null) return;
        executor.execute(message.unitId(), message.businessDate());
    }
}

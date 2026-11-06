package com.fintech.statement.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.statement.application.service.StatementBatchService;
import com.fintech.statement.application.service.StatementGenerationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Lo que el corte publica y este servicio escucha.
 *
 * <p>{@code cutoff-closed} es el trabajo: un mensaje por cuenta, con la cuenta como llave. Cada pod
 * del grupo genera los documentos de sus particiones; el paralelismo total es
 * {@code min(particiones, réplicas × concurrency)}. Los otros dos eventos sólo llevan la cuenta del
 * avance.
 */
@Component
public class CutoffEventsListener {

    private static final Logger log = LoggerFactory.getLogger(CutoffEventsListener.class);

    private final StatementGenerationService generation;
    private final StatementBatchService batches;
    private final ObjectMapper json;

    public CutoffEventsListener(StatementGenerationService generation, StatementBatchService batches, ObjectMapper json) {
        this.generation = generation;
        this.batches    = batches;
        this.json       = json;
    }

    @KafkaListener(
            topics = "closing.cutoff-closed",
            groupId = "${spring.kafka.consumer.group-id}",
            concurrency = "${fintech.statement.concurrency:4}",
            containerFactory = "cutoffClosedListenerContainerFactory")
    public void onCutoffClosed(CutoffClosedMessage message) {
        if (message == null || message.creditAccountId() == null || message.cutoffDate() == null) return;
        if (message.paymentDueDate() == null) {
            log.warn("cutoff-closed sin fecha límite de pago cuenta={} ciclo={} — ignorado",
                    message.creditAccountId(), message.cycleNumber());
            return;
        }
        generation.onCutoffClosed(message.toFacts());
    }

    @KafkaListener(
            topics = "closing.cutoff-run-planned",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "cutoffRunPlannedListenerContainerFactory")
    public void onRunPlanned(CutoffRunPlannedMessage message) throws Exception {
        if (message == null || message.runId() == null) return;
        batches.onRunPlanned(message.runId(), message.businessDate(), message.totalUnits(),
                json.writeValueAsString(message.breakdown()));
    }

    @KafkaListener(
            topics = "closing.cutoff-run-completed",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "cutoffRunCompletedListenerContainerFactory")
    public void onRunCompleted(CutoffRunCompletedMessage message) {
        if (message == null || message.runId() == null) return;
        batches.onRunCompleted(message.runId(), message.businessDate(), message.status(),
                message.doneUnits(), message.failedUnits());
    }
}

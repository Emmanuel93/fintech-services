package com.fintech.receipt.infrastructure.adapter.in.messaging;

import com.fintech.receipt.application.service.ReceiptGenerationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * The receipts' own consumer group on {@code closing.cutoff-closed}: it reads the same messages as statement-service,
 * at the same time and at its own pace, and only a distributor's line produces receipts.
 */
@Component
public class CutoffClosedListener {

    private static final Logger log = LoggerFactory.getLogger(CutoffClosedListener.class);

    private final ReceiptGenerationService generation;

    public CutoffClosedListener(ReceiptGenerationService generation) {
        this.generation = generation;
    }

    @KafkaListener(
            topics = "closing.cutoff-closed",
            groupId = "${spring.kafka.consumer.group-id}",
            concurrency = "${fintech.receipt.concurrency:4}",
            containerFactory = "cutoffClosedListenerContainerFactory")
    public void onCutoffClosed(CutoffClosedMessage message) {
        if (message == null || message.creditAccountId() == null || message.cutoffDate() == null) return;
        if (message.paymentDueDate() == null) {
            log.warn("cutoff-closed sin fecha límite de pago cuenta={} ciclo={}: sin recibos", message.creditAccountId(),
                    message.cycleNumber());
            return;
        }
        generation.onCutoffClosed(message.toFacts());
    }
}

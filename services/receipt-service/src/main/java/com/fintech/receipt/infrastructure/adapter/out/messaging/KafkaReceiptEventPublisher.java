package com.fintech.receipt.infrastructure.adapter.out.messaging;

import com.fintech.receipt.application.port.out.ReceiptEventPublisher;
import com.fintech.receipt.domain.Receipt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
class KafkaReceiptEventPublisher implements ReceiptEventPublisher {

    static final String TOPIC = "receipt.receipt-generated";

    private static final Logger log = LoggerFactory.getLogger(KafkaReceiptEventPublisher.class);

    private final KafkaTemplate<String, Object> kafka;

    KafkaReceiptEventPublisher(KafkaTemplate<String, Object> kafka) {
        this.kafka = kafka;
    }

    @Override
    public void publishGenerated(Receipt receipt) {
        // Keyed by the line: a distributor's receipts arrive in order.
        kafka.send(TOPIC, receipt.creditAccountId().toString(), ReceiptGeneratedPayload.of(receipt))
                .whenComplete((ok, ex) -> {
                    if (ex != null) log.error("receipt-generated no se publicó recibo={}: {}", receipt.receiptId(), ex.getMessage());
                });
    }
}

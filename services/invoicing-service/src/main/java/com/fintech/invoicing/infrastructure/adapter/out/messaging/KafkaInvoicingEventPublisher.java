package com.fintech.invoicing.infrastructure.adapter.out.messaging;

import com.fintech.invoicing.application.port.out.InvoicingEventPublisher;
import com.fintech.invoicing.domain.Invoice;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class KafkaInvoicingEventPublisher implements InvoicingEventPublisher {

    static final String TOPIC_INVOICE_GENERATED = "invoicing.invoice-generated";
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public KafkaInvoicingEventPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public void publishInvoiceGenerated(Invoice i) {
        var payload = new InvoiceGeneratedPayload(i.getInvoiceId(), i.getInvoiceRequestId(),
                i.getObligorPartyId(), i.getPeriod(), i.getReceptorRfc(), i.getTotal(),
                i.getStatus().name(), i.getFolioFiscal(), i.getStampedAt());
        kafkaTemplate.send(TOPIC_INVOICE_GENERATED, i.getInvoiceId().toString(), payload);
    }
}

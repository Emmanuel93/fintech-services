package com.fintech.invoicing.infrastructure.adapter.in.messaging;

import com.fintech.invoicing.application.InvoiceRequestCommand;
import com.fintech.invoicing.application.service.InvoiceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class InvoiceRequestedListener {

    private static final Logger log = LoggerFactory.getLogger(InvoiceRequestedListener.class);
    private final InvoiceService invoiceService;

    public InvoiceRequestedListener(InvoiceService invoiceService) { this.invoiceService = invoiceService; }

    @KafkaListener(topics = "accounting.invoice-requested",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "invoiceRequestedListenerContainerFactory")
    public void onMessage(InvoiceRequestedPayload p) {
        log.debug("invoice-requested requestId={} partyId={} total={}", p.invoiceRequestId(), p.obligorPartyId(), p.total());
        var lines = p.lines() == null ? java.util.List.<InvoiceRequestCommand.Line>of()
                : p.lines().stream()
                    .map(l -> new InvoiceRequestCommand.Line(l.concept(), l.creditAccountId(), l.amount(), l.isIva()))
                    .toList();
        invoiceService.onInvoiceRequested(new InvoiceRequestCommand(
                p.invoiceRequestId(), p.obligorPartyId(), p.period(), lines, p.subtotal(), p.iva(), p.total()));
    }
}

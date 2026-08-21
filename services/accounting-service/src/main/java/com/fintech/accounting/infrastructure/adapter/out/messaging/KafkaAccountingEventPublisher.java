package com.fintech.accounting.infrastructure.adapter.out.messaging;

import com.fintech.accounting.application.InvoiceRequest;
import com.fintech.accounting.application.port.out.AccountingEventPublisher;
import com.fintech.accounting.domain.JournalEntry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Component
public class KafkaAccountingEventPublisher implements AccountingEventPublisher {

    static final String TOPIC_JOURNAL_ENTRY  = "accounting.journal-entry-created";
    static final String TOPIC_INVOICE_REQUEST = "accounting.invoice-requested";
    static final String TOPIC_RECON_ALERT     = "accounting.reconciliation-alert";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public KafkaAccountingEventPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public void publishJournalEntryCreated(JournalEntry e) {
        var payload = new JournalEntryCreatedPayload(e.getEntryId(), e.getSourceEventId(), e.getTriggerEvent(),
                e.getCreditAccountId(), e.getObligorPartyId(), e.getDebitAccount(), e.getCreditAccount(),
                e.getAmount(), e.getCurrency(), e.getPeriod(), e.getPostingDate());
        kafkaTemplate.send(TOPIC_JOURNAL_ENTRY, e.getEntryId().toString(), payload);
    }

    @Override
    public void publishInvoiceRequested(InvoiceRequest r) {
        var lines = r.lines().stream()
                .map(l -> new InvoiceRequestedPayload.LinePayload(l.concept(), l.creditAccountId(), l.amount(), l.isIva()))
                .toList();
        var payload = new InvoiceRequestedPayload(r.invoiceRequestId(), r.obligorPartyId(), r.period(),
                lines, r.subtotal(), r.iva(), r.total());
        kafkaTemplate.send(TOPIC_INVOICE_REQUEST, r.obligorPartyId().toString(), payload);
    }

    @Override
    public void publishReconciliationAlert(String checkType, UUID creditAccountId, BigDecimal operational,
                                           BigDecimal accounting, BigDecimal delta, String period) {
        var payload = new ReconciliationAlertPayload(checkType, creditAccountId, operational, accounting,
                delta, period, Instant.now());
        kafkaTemplate.send(TOPIC_RECON_ALERT, checkType + ":" + (creditAccountId != null ? creditAccountId : period), payload);
    }
}

package com.fintech.accounting.application.port.out;

import com.fintech.accounting.application.InvoiceRequest;
import com.fintech.accounting.domain.JournalEntry;

import java.math.BigDecimal;
import java.util.UUID;

public interface AccountingEventPublisher {
    void publishJournalEntryCreated(JournalEntry entry);
    void publishInvoiceRequested(InvoiceRequest request);
    void publishReconciliationAlert(String checkType, UUID creditAccountId, BigDecimal operational,
                                    BigDecimal accounting, BigDecimal delta, String period);
}

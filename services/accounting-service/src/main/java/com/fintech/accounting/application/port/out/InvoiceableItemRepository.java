package com.fintech.accounting.application.port.out;

import com.fintech.accounting.domain.InvoiceableItem;
import com.fintech.accounting.domain.InvoiceableItemStatus;

import java.util.List;
import java.util.UUID;

public interface InvoiceableItemRepository {
    boolean existsBySourceEventId(String sourceEventId);
    List<InvoiceableItem> findByStatusAndPeriod(InvoiceableItemStatus status, String period);
    List<InvoiceableItem> findByObligorPartyIdAndPeriodAndStatus(UUID obligorPartyId, String period, InvoiceableItemStatus status);
    InvoiceableItem save(InvoiceableItem item);
}

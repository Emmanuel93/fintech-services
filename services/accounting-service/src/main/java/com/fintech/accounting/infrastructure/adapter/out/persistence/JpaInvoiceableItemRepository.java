package com.fintech.accounting.infrastructure.adapter.out.persistence;

import com.fintech.accounting.application.port.out.InvoiceableItemRepository;
import com.fintech.accounting.domain.InvoiceableItem;
import com.fintech.accounting.domain.InvoiceableItemStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface JpaInvoiceableItemRepository
        extends JpaRepository<InvoiceableItem, UUID>, InvoiceableItemRepository {

    @Override
    boolean existsBySourceEventId(String sourceEventId);

    @Override
    List<InvoiceableItem> findByStatusAndPeriod(InvoiceableItemStatus status, String period);

    @Override
    List<InvoiceableItem> findByObligorPartyIdAndPeriodAndStatus(UUID obligorPartyId, String period, InvoiceableItemStatus status);
}

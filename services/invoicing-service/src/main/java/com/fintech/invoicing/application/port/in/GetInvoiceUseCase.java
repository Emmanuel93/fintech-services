package com.fintech.invoicing.application.port.in;

import com.fintech.invoicing.domain.Invoice;
import com.fintech.invoicing.domain.InvoiceStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

public interface GetInvoiceUseCase {
    Invoice getById(UUID invoiceId);
    List<Invoice> getByPartyId(UUID obligorPartyId);
    Page<Invoice> search(String period, InvoiceStatus status, UUID partyId,
                         UUID creditAccountId, Pageable pageable);
}

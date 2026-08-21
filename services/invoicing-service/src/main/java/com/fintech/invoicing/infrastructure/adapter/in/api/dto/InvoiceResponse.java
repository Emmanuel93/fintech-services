package com.fintech.invoicing.infrastructure.adapter.in.api.dto;

import com.fintech.invoicing.domain.Invoice;
import com.fintech.invoicing.domain.InvoiceLine;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record InvoiceResponse(
        UUID invoiceId,
        UUID invoiceRequestId,
        UUID obligorPartyId,
        String period,
        String receptorRfc,
        String receptorName,
        String receptorRegime,
        String cfdiUse,
        BigDecimal subtotal,
        BigDecimal iva,
        BigDecimal total,
        String currency,
        String status,
        UUID folioFiscal,
        String serie,
        Long folio,
        Instant stampedAt,
        List<Line> lines
) {
    public record Line(String concept, UUID creditAccountId, BigDecimal amount, boolean isIva) {}

    public static InvoiceResponse from(Invoice i) {
        List<Line> lines = i.getLines().stream()
                .map(l -> new Line(l.getConcept(), l.getCreditAccountId(), l.getAmount(), l.isIva()))
                .toList();
        return new InvoiceResponse(i.getInvoiceId(), i.getInvoiceRequestId(), i.getObligorPartyId(),
                i.getPeriod(), i.getReceptorRfc(), i.getReceptorName(), i.getReceptorRegime(), i.getCfdiUse(),
                i.getSubtotal(), i.getIva(), i.getTotal(), i.getCurrency(), i.getStatus().name(),
                i.getFolioFiscal(), i.getSerie(), i.getFolio(), i.getStampedAt(), lines);
    }
}

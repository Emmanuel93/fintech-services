package com.fintech.accounting.application;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Solicitud de facturación consolidada (un CFDI por party/período) enviada a Facturación. */
public record InvoiceRequest(
        UUID invoiceRequestId,
        UUID obligorPartyId,
        String period,
        List<Line> lines,
        BigDecimal subtotal,
        BigDecimal iva,
        BigDecimal total
) {
    public record Line(String concept, UUID creditAccountId, BigDecimal amount, boolean isIva) {}
}

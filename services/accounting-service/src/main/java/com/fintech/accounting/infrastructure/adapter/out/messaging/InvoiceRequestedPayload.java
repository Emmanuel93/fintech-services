package com.fintech.accounting.infrastructure.adapter.out.messaging;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Outbound {@code accounting.invoice-requested} — consumido por el servicio de Facturación. */
public record InvoiceRequestedPayload(
        UUID invoiceRequestId,
        UUID obligorPartyId,
        String period,
        List<LinePayload> lines,
        BigDecimal subtotal,
        BigDecimal iva,
        BigDecimal total
) {
    public record LinePayload(String concept, UUID creditAccountId, BigDecimal amount, boolean isIva) {}
}

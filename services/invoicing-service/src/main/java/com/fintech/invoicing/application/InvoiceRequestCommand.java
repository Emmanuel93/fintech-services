package com.fintech.invoicing.application;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record InvoiceRequestCommand(
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

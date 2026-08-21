package com.fintech.invoicing.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record InvoiceRequestedPayload(
        UUID invoiceRequestId,
        UUID obligorPartyId,
        String period,
        List<LinePayload> lines,
        BigDecimal subtotal,
        BigDecimal iva,
        BigDecimal total
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LinePayload(String concept, UUID creditAccountId, BigDecimal amount, boolean isIva) {}
}

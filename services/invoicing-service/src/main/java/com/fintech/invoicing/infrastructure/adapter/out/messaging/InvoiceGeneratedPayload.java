package com.fintech.invoicing.infrastructure.adapter.out.messaging;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record InvoiceGeneratedPayload(
        UUID invoiceId,
        UUID invoiceRequestId,
        UUID obligorPartyId,
        String period,
        String receptorRfc,
        BigDecimal total,
        String status,
        UUID folioFiscal,
        Instant stampedAt
) {}

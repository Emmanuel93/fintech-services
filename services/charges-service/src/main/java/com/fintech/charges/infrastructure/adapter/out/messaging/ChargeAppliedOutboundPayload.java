package com.fintech.charges.infrastructure.adapter.out.messaging;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record ChargeAppliedOutboundPayload(
        String eventId,
        UUID creditAccountId,
        String chargeType,
        BigDecimal totalAmount,
        /** El día al que pertenece el cargo. Contabilidad deriva de aquí el período de la póliza. */
        LocalDate effectiveDate,
        /** El cierre del día que lo generó; nulo si no salió de uno. Es el lote que cartera cuadra. */
        LocalDate dayCloseDate
) {}

package com.fintech.banking.application.port.in;

import com.fintech.banking.domain.PayoutDecision;
import com.fintech.banking.domain.PayoutRail;

import java.math.BigDecimal;
import java.util.UUID;

/** «¿Por dónde sale este pago?» — cuenta, rail y proveedor en una sola respuesta. */
public interface ResolvePayoutRouteUseCase {

    record Peticion(UUID companyId, PayoutRail rail, BigDecimal amount) {}

    PayoutDecision resolver(Peticion peticion);
}

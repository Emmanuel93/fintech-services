package com.fintech.charges.infrastructure.adapter.out.messaging;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * {@code charges.day-accrued}: el devengo ordinario y moratorio de {@code businessDate} terminó.
 *
 * <p>Lleva las cifras de control del día —cuántos cargos de interés salieron y por cuánto—. Cartera
 * cuenta lo que aplicó (más lo que rechazó) y no cierra el día hasta que cuadra.
 *
 * <p>{@code heldAccounts}: las cuentas que no devengaron el día —en cuarentena o retenidas—. Cartera cuadra
 * sin ellas y closing retiene su corte hasta que devenguen ({@code charges.account-accrued}).
 */
public record DayAccruedOutboundPayload(
        String eventId,
        LocalDate businessDate,
        int ordinarySchedules,
        int ordinaryErrors,
        int moratoriumSchedules,
        int moratoriumErrors,
        int ordinaryCharges,
        BigDecimal ordinaryAmount,
        int moratoriumCharges,
        BigDecimal moratoriumAmount,
        long elapsedMs,
        Instant completedAt,
        int accounts,
        List<UUID> heldAccounts
) {}

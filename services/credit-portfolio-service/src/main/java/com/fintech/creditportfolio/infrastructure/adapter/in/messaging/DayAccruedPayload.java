package com.fintech.creditportfolio.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Inbound de charges ({@code charges.day-accrued}): el devengo ordinario y moratorio de ese día terminó,
 * con sus cifras de control —cuántos cargos de interés salieron y por cuánto— y las cuentas que quedaron
 * fuera del lote ({@code heldAccounts}).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DayAccruedPayload(String eventId, LocalDate businessDate, int ordinarySchedules, int ordinaryErrors,
                                int moratoriumSchedules, int moratoriumErrors,
                                int ordinaryCharges, BigDecimal ordinaryAmount,
                                int moratoriumCharges, BigDecimal moratoriumAmount,
                                int accounts, List<UUID> heldAccounts) {}

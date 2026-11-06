package com.fintech.charges.infrastructure.adapter.out.messaging;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * {@code charges.account-accrued}: una cuenta que el aviso de su día dejó fuera —en cuarentena o
 * retenida— ya devengó, con sus cifras de control. Cartera cuadra esa cuenta antes de avisar a closing.
 */
public record AccountAccruedOutboundPayload(String eventId, LocalDate businessDate, UUID creditAccountId,
                                            int ordinaryCharges, BigDecimal ordinaryAmount,
                                            int moratoriumCharges, BigDecimal moratoriumAmount) {}

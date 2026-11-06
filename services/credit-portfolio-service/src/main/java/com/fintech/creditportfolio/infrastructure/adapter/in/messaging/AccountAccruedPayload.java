package com.fintech.creditportfolio.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Inbound de charges ({@code charges.account-accrued}): una cuenta que el cierre de su día dejó fuera ya
 * devengó, con sus cifras de control.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AccountAccruedPayload(String eventId, LocalDate businessDate, UUID creditAccountId,
                                    int ordinaryCharges, BigDecimal ordinaryAmount,
                                    int moratoriumCharges, BigDecimal moratoriumAmount) {}

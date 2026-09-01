package com.fintech.creditportfolio.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Forma de {@code closing.cutoff-closed}.
 *
 * <p>{@code paymentDueDate} es el dato clave: es <b>la fecha exigible de una revolvente</b>. Un
 * producto a plazo la saca de su plan de amortización; una línea o una tarjeta no tienen plan, y
 * sin esta fecha no tendrían ninguna (BK-22).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CutoffClosedPayload(String eventId,
                                  UUID creditAccountId,
                                  UUID obligorPartyId,
                                  String productType,
                                  int cycleNumber,
                                  LocalDate cutoffDate,
                                  LocalDate paymentDueDate,
                                  BigDecimal balanceAtCutoff,
                                  BigDecimal amountDue,
                                  BigDecimal minimumPayment) {}

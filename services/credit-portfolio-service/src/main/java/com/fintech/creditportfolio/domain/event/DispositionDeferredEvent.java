package com.fintech.creditportfolio.domain.event;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * El titular difirió una compra: deja de ser exigible en el corte y pasa a tener plan.
 *
 * <p>Lleva la <b>ventana</b> —de cuándo a cuándo fue revolvente— porque es lo que {@code charges}
 * necesita para saber cuánto interés reversar. Cartera no calcula ese importe: lo devengó charges y
 * es charges quien sabe con qué tasa y sobre qué base lo hizo.
 *
 * @param nominalRate la tasa del plan. Cero es válido y es exactamente lo que un MSI es
 */
public record DispositionDeferredEvent(UUID dispositionId,
                                       UUID creditAccountId,
                                       UUID obligorPartyId,
                                       BigDecimal amount,
                                       int termPeriods,
                                       BigDecimal nominalRate,
                                       LocalDate revolvingDesde,
                                       LocalDate revolvingHasta) {}

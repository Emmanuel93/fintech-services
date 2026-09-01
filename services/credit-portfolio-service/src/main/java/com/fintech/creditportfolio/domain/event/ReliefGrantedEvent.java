package com.fintech.creditportfolio.domain.event;

import java.time.LocalDate;
import java.util.UUID;

/**
 * A esta cuenta se le otorgó un programa de apoyo.
 *
 * <p>Lo consume {@code risk} para marcar forborne —piso IFRS-9 STAGE_2 y reloj de cura reiniciado,
 * como cualquier reestructura— y {@code collections} para no abrir caso durante la vigencia.
 *
 * <p><b>La reserva sube.</b> Es el costo asumido del apoyo, y va escrito aquí para que quien
 * consuma el evento no lo descubra como sorpresa.
 */
public record ReliefGrantedEvent(UUID reliefProgramId,
                                 String programName,
                                 String reason,
                                 UUID creditAccountId,
                                 UUID obligorPartyId,
                                 int deferredPeriods,
                                 LocalDate reliefValidTo,
                                 boolean accrualWaived,
                                 LocalDate firstDueBefore,
                                 LocalDate firstDueAfter) {}

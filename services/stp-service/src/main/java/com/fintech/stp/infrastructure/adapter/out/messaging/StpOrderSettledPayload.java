package com.fintech.stp.infrastructure.adapter.out.messaging;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * El dinero llegó. Único evento que lo afirma.
 *
 * <p><b>Lleva el importe, la fecha de operación y un id de evento estable</b>, y no es adorno. Tesorería
 * (banking) proyecta este hecho como el cargo que el banco va a reportar, y sin importe no hay nada
 * que cruzar: descartaba <i>todas</i> las liquidaciones con «sin clave o importe id=null» y la
 * conciliación nunca tuvo un solo movimiento interno contra el cual cruzar. El conector es quien
 * conoce el importe de la orden; pedírselo a otro servicio sería reconstruir lo que aquí ya se sabe.
 *
 * <p>{@code eventId} es <b>determinista</b> —el mismo para la misma orden— porque los consumidores
 * deduplican por él: un id aleatorio por envío convertiría cada reentrega en un hecho nuevo.
 * {@code businessDate} es el día Banxico de la orden, que es como el banco la reporta, no el día en
 * que se observó la liquidación. {@code registeredOn} is the day the order was registered, in Mexico City: the
 * portfolio's date of the money, which with {@code businessDate} (the bank's) shows the order in transit between them.
 */
record StpOrderSettledPayload(
        String eventId,
        UUID paymentRequestId,
        UUID companyId,
        String trackingKey,
        BigDecimal amount,
        LocalDate businessDate,
        LocalDate registeredOn,
        String cepUrl,
        String cepBeneficiaryName,
        boolean beneficiaryNameMatches,
        Instant settledAt,
        String observedVia,
        Instant occurredOn
) {}

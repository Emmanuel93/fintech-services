package com.fintech.banking.application.port.out;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Lo que la plataforma dice que pasó, para cruzarlo contra lo que dice el banco.
 *
 * <p>Es un <b>puerto</b> y no una consulta directa: banking no conoce el dominio de crédito ni el de
 * pagos. Recibe hechos con su referencia opaca y los cruza por importe, fecha y clave de rastreo.
 */
public interface InternalMovementLookupPort {

    /**
     * @param type      {@code STP_ORDER} · {@code FUNDING} · {@code CLIENT_PAYMENT} · {@code PAYMENT_RETURN} ·
     *                  {@code COMMISSION_PAYOUT} · {@code PAYMENT_ORDER} · {@code DISBURSEMENT_ORDER} ·
     *                  {@code JOURNAL_ENTRY} · {@code MANUAL_ADJUSTMENT}
     * @param direction {@code CREDIT} (money in) or {@code DEBIT} (money out), as the bank's statement says it; null
     *                  when unknown
     */
    record MovimientoInterno(String type, String reference, BigDecimal amount,
                             LocalDate businessDate, String trackingKey, String direction) {

        public MovimientoInterno(String type, String reference, BigDecimal amount, LocalDate businessDate,
                                 String trackingKey) {
            this(type, reference, amount, businessDate, trackingKey, null);
        }

        /** Whether it can be the same money as a statement line going {@code lineDirection}. */
        public boolean goes(String lineDirection) {
            return direction == null || lineDirection == null || direction.equals(lineDirection);
        }
    }

    /** El cruce sin margen de error: la clave de rastreo es única por orden. */
    Optional<MovimientoInterno> byTrackingKey(String trackingKey);

    /** Candidatos por importe y fecha. Puede devolver varios — y eso es información, no un fallo. */
    List<MovimientoInterno> byAmountAndDate(BigDecimal amount, LocalDate businessDate);
}

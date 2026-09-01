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
     * @param type {@code PAYMENT_ORDER} · {@code DISBURSEMENT_ORDER} · {@code STP_ORDER} ·
     *             {@code JOURNAL_ENTRY} · {@code MANUAL_ADJUSTMENT}
     */
    record MovimientoInterno(String type, String reference, BigDecimal amount,
                             LocalDate businessDate, String trackingKey) {}

    /** El cruce sin margen de error: la clave de rastreo es única por orden. */
    Optional<MovimientoInterno> porClaveDeRastreo(String trackingKey);

    /** Candidatos por importe y fecha. Puede devolver varios — y eso es información, no un fallo. */
    List<MovimientoInterno> porImporteYFecha(BigDecimal amount, LocalDate businessDate);
}

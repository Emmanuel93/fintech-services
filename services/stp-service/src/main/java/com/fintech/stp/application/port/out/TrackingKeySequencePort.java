package com.fintech.stp.application.port.out;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Secuencia de la clave de rastreo, por empresa y día.
 *
 * <p>No es una {@code SEQUENCE} de Postgres: hay que reiniciar por día y el valor tiene que ser
 * transaccional con la orden. El legado usaba el PK autoincremental de la tabla, atando el
 * identificador ante Banxico a un detalle de implementación.
 */
public interface TrackingKeySequencePort {

    long next(UUID companyId, LocalDate businessDate);
}

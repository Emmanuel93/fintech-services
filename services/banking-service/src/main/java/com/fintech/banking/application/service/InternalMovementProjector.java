package com.fintech.banking.application.service;

import com.fintech.banking.domain.InternalMovement;

/**
 * Proyecta los hechos internos que la conciliación cruza.
 *
 * <p>Se declara como puerto de aplicación para que los listeners no dependan del adaptador JPA: lo
 * que llega por Kafka es vocabulario de pagos, y traducirlo a una fila es responsabilidad de aquí.
 */
public interface InternalMovementProjector {

    void proyectar(InternalMovement movimiento);
}

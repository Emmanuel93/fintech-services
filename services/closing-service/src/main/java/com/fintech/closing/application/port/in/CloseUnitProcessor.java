package com.fintech.closing.application.port.in;

import com.fintech.closing.domain.ClosePhase;
import com.fintech.closing.domain.CloseUnit;

import java.time.LocalDate;

/**
 * Lo que se hace con una unidad. Una implementación por fase.
 *
 * <p>El motor no sabe devengar ni calcular mora: reparte y llama aquí. Es lo que permite que
 * añadir una fase sea escribir un procesador, no tocar el motor.
 */
public interface CloseUnitProcessor {

    ClosePhase phase();

    /**
     * @throws Exception cualquier fallo marca la unidad {@code FAILED} con su causa y deja la
     *         corrida viva. Una cuenta que falla no puede tumbar el cierre de las demás.
     */
    void process(CloseUnit unit, LocalDate businessDate) throws Exception;
}

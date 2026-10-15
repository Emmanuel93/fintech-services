package com.fintech.compliance.application.port.out;

import com.fintech.compliance.domain.PersonVerification;

/**
 * Publica el veredicto para quien tenga que actuar sobre él.
 *
 * <p>Dos consumidores previstos y con papeles distintos: <b>`party`</b> lo guarda —es dueño de la
 * persona y es a quien consultan las compuertas— y <b>`audit`</b> deja constancia. Esa separación es
 * deliberada: D16 decide, T3 registra. Escribir el `UIFReport` desde aquí sería escribir en la base
 * de otro servicio.
 */
public interface VerificationEventPublisher {

    /** Cada vez que el veredicto cambia: al correrse el screening y al adjudicarlo una persona. */
    void publishVerificationCompleted(PersonVerification verification);

    /**
     * Una coincidencia <b>confirmada</b> por una persona.
     *
     * <p>Va aparte del anterior porque obliga a algo más que rechazar: genera una obligación de
     * reporte. `audit` la consume y escribe el `UIFReport`, que es de quien es la tabla.
     */
    void publishConfirmedMatch(PersonVerification verification, String listedName, String program);
}

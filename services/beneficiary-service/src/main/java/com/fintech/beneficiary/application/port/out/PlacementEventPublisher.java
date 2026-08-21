package com.fintech.beneficiary.application.port.out;

import com.fintech.beneficiary.domain.event.PlacementEvent;

/**
 * Publica los eventos del ciclo de vida de una colocación.
 *
 * <p>Un solo método, y no uno por evento, porque el destino ya viaja en el propio evento
 * ({@code PlacementEvent.topic()}). Once métodos que sólo difieren en la constante del tópico
 * serían once oportunidades de mandar el evento equivocado al lugar equivocado.
 */
public interface PlacementEventPublisher {
    void publish(PlacementEvent event);
}

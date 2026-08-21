package com.fintech.beneficiary.domain.event;

import com.fintech.shared.event.DomainEvent;

import java.util.UUID;

/**
 * Base de los eventos del ciclo de vida de una colocación.
 *
 * <p>Cada transición tiene su tópico propio, y no uno solo con el estado adentro, por dos razones
 * concretas: audit-service es suscriptor global y necesita distinguirlos para clasificar el asiento
 * regulatorio, y notifications enruta por {@code EventType} —el mensaje de «ya está su historial»
 * no puede salir por la misma política que el de «te depositamos».
 *
 * <p>Los tipos concretos son cinco y no once porque sólo cuatro transiciones cargan datos propios
 * (invitación, aprobación, desembolso y la constancia de buró); las demás se distinguen por tópico
 * y comparten forma. Once clases idénticas salvo el nombre serían ruido, no tipado.
 */
public abstract class PlacementEvent extends DomainEvent {

    private final UUID placementId;
    private final UUID distributorPartyId;

    protected PlacementEvent(UUID placementId, UUID distributorPartyId, String correlationId) {
        super(correlationId);
        this.placementId        = placementId;
        this.distributorPartyId = distributorPartyId;
    }

    /** El tópico Kafka al que va este evento. */
    public abstract String topic();

    public UUID getPlacementId()        { return placementId; }
    public UUID getDistributorPartyId() { return distributorPartyId; }
}

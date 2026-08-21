package com.fintech.beneficiary.infrastructure.adapter.in.messaging;

import com.fintech.beneficiary.application.port.in.PlacementLifecycleUseCase;
import com.fintech.beneficiary.application.port.out.PlacementRepository;
import com.fintech.beneficiary.domain.Placement;
import com.fintech.beneficiary.domain.PlacementStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * El dinero llegó a la cuenta de la beneficiaria.
 *
 * <p>Cierra el único tramo del flujo que no depende de nadie de este lado: entre que el
 * distribuidor aprueba y que el SPEI se acredita pasan minutos, y hasta que credit-portfolio lo
 * confirma la colocación sigue en {@code DISBURSING}. Sin este listener se quedaría ahí para
 * siempre —la app la pintaría «DEPOSITANDO» eternamente— aunque el dinero ya estuviera en su
 * cuenta.
 *
 * <p>Se filtra por {@code THIRD_PARTY_CREDIT}: la misma línea del distribuidor puede tener
 * disposiciones para su propio uso, y ésas no son colocaciones de nadie.
 */
@Component
public class DispositionCompletedListener {

    private static final Logger log = LoggerFactory.getLogger(DispositionCompletedListener.class);

    private final PlacementRepository placements;
    private final PlacementLifecycleUseCase lifecycle;

    public DispositionCompletedListener(PlacementRepository placements,
                                        PlacementLifecycleUseCase lifecycle) {
        this.placements = placements;
        this.lifecycle  = lifecycle;
    }

    @KafkaListener(
            topics = "credit-portfolio.disposition-completed",
            groupId = "${spring.kafka.consumer.group-id}")
    public void onDispositionCompleted(DispositionCompletedPayload event) {
        if (event == null || event.creditAccountId() == null) return;
        if (!"THIRD_PARTY_CREDIT".equalsIgnoreCase(event.dispositionType())) return;

        Optional<Placement> match = placements
                .findByDistributorPartyIdOrderByCreatedAtDesc(event.obligorPartyId())
                .stream()
                // APPROVED además de DISBURSING, y no es laxitud: entre que se pide la disposición
                // y que el agregado registra que la pidió pasan milisegundos, y credit-portfolio
                // puede completarla dentro de esa ventana. Aceptar sólo DISBURSING dejaba la
                // colocación esperando un evento que ya había pasado — desembolsada de verdad y
                // pintada «depositando» para siempre.
                .filter(p -> p.getStatus() == PlacementStatus.DISBURSING
                          || p.getStatus() == PlacementStatus.APPROVED)
                .filter(p -> p.getDistributorCreditAccountId().equals(event.creditAccountId()))
                // El monto desempata cuando hay varias colocaciones en vuelo contra la misma
                // línea. No es una llave perfecta —dos por el mismo importe el mismo día
                // colisionarían— y por eso la correlación definitiva es el `dispositionId`, que
                // llegará cuando wallet lo devuelva en la respuesta de la disposición.
                .filter(p -> p.getAmount().compareTo(
                        event.amount() == null ? BigDecimal.ZERO : event.amount()) == 0)
                .findFirst();

        if (match.isEmpty()) {
            log.debug("disposition-completed {} sin colocación en vuelo que la reclame",
                    event.dispositionId());
            return;
        }

        UUID placementId = match.get().getPlacementId();

        // Dos intentos, y el segundo no es paranoia: la aprobación por HTTP también está moviendo
        // esta misma colocación a DISBURSING en este instante. Cuando las dos coinciden, una
        // pierde el lock optimista — y la que pierde suele ser ésta, dejando la colocación
        // desembolsada de verdad y marcada «depositando» para siempre. Al reintentar, la segunda
        // pasada ya encuentra el estado que la otra escribió y sólo cierra el desembolso.
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                Placement fresh = lifecycle.findById(placementId);
                if (fresh.getStatus() == PlacementStatus.DISBURSED
                        || fresh.getStatus() == PlacementStatus.PAID_OFF) {
                    return; // Kafka reentrega; ya estaba cerrada.
                }
                if (fresh.getStatus() == PlacementStatus.APPROVED) {
                    lifecycle.markDisbursing(placementId,
                            event.dispositionId() == null ? placementId : event.dispositionId());
                } else {
                    // El camino normal: la aprobación por HTTP ya dejó la colocación en DISBURSING,
                    // pero con el **marcador** —el propio placementId— porque cuando la pidió aún no
                    // existía la disposición. Este evento trae el id real y es el único momento en
                    // que se puede corregir. Antes sólo se aplicaba en la rama APPROVED, que cubre
                    // la carrera y no el caso común, así que el marcador se quedaba para siempre y
                    // la colocación nunca encontraba su calendario.
                    lifecycle.reconcileDispositionId(placementId, event.dispositionId());
                }
                lifecycle.markDisbursed(placementId);
                log.info("Colocación {} desembolsada por la disposición {}",
                        placementId, event.dispositionId());
                return;
            } catch (RuntimeException e) {
                if (attempt == 0) {
                    log.debug("Reintentando el cierre de la colocación {}: {}",
                            placementId, e.getMessage());
                    try {
                        Thread.sleep(400);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                } else {
                    log.warn("No se pudo marcar desembolsada la colocación {}: {}",
                            placementId, e.getMessage());
                }
            }
        }
    }

    /** Lo que este servicio necesita del evento; el resto se ignora. */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record DispositionCompletedPayload(
            UUID dispositionId,
            UUID creditAccountId,
            UUID obligorPartyId,
            BigDecimal amount,
            String dispositionType,
            Instant occurredAt) {}
}

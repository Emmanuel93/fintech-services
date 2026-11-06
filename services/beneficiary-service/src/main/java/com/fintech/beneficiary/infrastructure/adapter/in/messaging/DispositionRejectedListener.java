package com.fintech.beneficiary.infrastructure.adapter.in.messaging;

import com.fintech.beneficiary.domain.PlacementRefusal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fintech.beneficiary.application.port.in.PlacementLifecycleUseCase;
import com.fintech.beneficiary.application.port.out.PlacementRepository;
import com.fintech.beneficiary.domain.Placement;
import com.fintech.beneficiary.domain.PlacementStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/**
 * credit-portfolio.disposition-rejected → a placement the portfolio refused (the line is blocked by arrears, the
 * amount or the term is outside the product, no available credit, an AML verdict) ends FAILED with the portfolio's
 * reason. Without this, nothing consumed the rejection and the placement waited as «depositando» for a payment that
 * would never be authorized.
 *
 * <p>Matched as {@code disposition-failed} matches: the distributor's line and the amount, the newest placement in
 * flight first.
 */
@Component
public class DispositionRejectedListener {

    private static final Logger log = LoggerFactory.getLogger(DispositionRejectedListener.class);

    private final PlacementRepository placements;
    private final PlacementLifecycleUseCase lifecycle;

    public DispositionRejectedListener(PlacementRepository placements, PlacementLifecycleUseCase lifecycle) {
        this.placements = placements;
        this.lifecycle = lifecycle;
    }

    @KafkaListener(
            topics = "credit-portfolio.disposition-rejected",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "dispositionRejectedListenerContainerFactory")
    public void onDispositionRejected(DispositionRejectedPayload event) {
        if (event == null || event.creditAccountId() == null || event.obligorPartyId() == null) return;
        Optional<Placement> match = placements.findByDistributorPartyIdOrderByCreatedAtDesc(event.obligorPartyId())
                .stream()
                .filter(p -> p.getStatus() == PlacementStatus.DISBURSING || p.getStatus() == PlacementStatus.APPROVED)
                .filter(p -> event.creditAccountId().equals(p.getDistributorCreditAccountId()))
                .filter(p -> event.rejectedAmount() == null || p.getAmount().compareTo(event.rejectedAmount()) == 0)
                .findFirst();
        if (match.isEmpty()) {
            log.debug("disposition-rejected {} sin colocación en vuelo que la reclame", event.sourceEventId());
            return;
        }
        lifecycle.fail(match.get().getPlacementId(), reasonFor(event.reason()));
        log.warn("Colocación {} rechazada por cartera: {}", match.get().getPlacementId(), event.reason());
    }

    /** The portfolio's reason, in the distributor's words. */
    static String reasonFor(String reason) {
        String r = reason == null ? "" : reason;
        if (r.startsWith("LINE_BLOCKED_IN_ARREARS")) {
            return "Tu línea está bloqueada por atraso: paga lo vencido para volver a colocar";
        }
        if (r.startsWith("LINE_CLOSED_AFTER_QUITA")) {
            return "Tu línea quedó cerrada por el convenio de quita: ya no coloca";
        }
        // The client's side (his type's limits, the red list) before the product's ranges: AMOUNT_ABOVE_CLIENT_LIMIT
        // starts like the product's AMOUNT_ codes. The event says nothing of whose marks they are: the red list is
        // told as Kredius not lending to him today.
        if (PlacementRefusal.isAboutTheClient(r)) return PlacementRefusal.inHerWords(r, false);
        if (r.startsWith("AMOUNT_")) return "El monto no está dentro de lo que permite el producto";
        if (r.startsWith("TERM_")) return "El plazo no está dentro de lo que permite el producto";
        if (r.startsWith("INSUFFICIENT_AVAILABLE_CREDIT")) return "Tu línea no tiene disponible suficiente";
        if (r.startsWith("AML_")) return "La colocación no pasó la revisión de listas";
        return "Cartera no autorizó la colocación (" + r + ")";
    }

    /** What beneficiary reads of {@code credit-portfolio.disposition-rejected}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DispositionRejectedPayload(String eventId, String sourceEventId, UUID creditAccountId, UUID obligorPartyId,
                                             BigDecimal rejectedAmount, String reason) {}
}

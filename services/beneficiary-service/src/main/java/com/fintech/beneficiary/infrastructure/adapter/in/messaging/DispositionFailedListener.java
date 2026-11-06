package com.fintech.beneficiary.infrastructure.adapter.in.messaging;

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
 * credit-portfolio.disposition-failed → a placement whose SPEI to the beneficiary could not happen ends FAILED, with
 * its reason (D63). The portfolio gave the distributor's line its credit back and cancelled the placement's plan;
 * without this, the placement waited as «depositando» for a payment that would never arrive.
 *
 * <p>Matched as {@code disposition-completed} matches it: the distributor, its line and the amount, the disposition id
 * first when the placement already holds it.
 */
@Component
public class DispositionFailedListener {

    private static final Logger log = LoggerFactory.getLogger(DispositionFailedListener.class);

    private final PlacementRepository placements;
    private final PlacementLifecycleUseCase lifecycle;

    public DispositionFailedListener(PlacementRepository placements, PlacementLifecycleUseCase lifecycle) {
        this.placements = placements;
        this.lifecycle = lifecycle;
    }

    @KafkaListener(
            topics = "credit-portfolio.disposition-failed",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "dispositionFailedListenerContainerFactory")
    public void onDispositionFailed(DispositionFailedPayload event) {
        if (event == null || event.creditAccountId() == null) return;
        if (!"THIRD_PARTY_CREDIT".equalsIgnoreCase(event.dispositionType())) return;

        Optional<Placement> match = placements
                .findByDistributorPartyIdOrderByCreatedAtDesc(event.obligorPartyId())
                .stream()
                .filter(p -> p.getStatus() == PlacementStatus.DISBURSING
                          || p.getStatus() == PlacementStatus.APPROVED)
                .filter(p -> p.getDistributorCreditAccountId().equals(event.creditAccountId()))
                .filter(p -> event.dispositionId() != null && event.dispositionId().equals(p.getDispositionId())
                          || p.getAmount().compareTo(event.amount() == null ? BigDecimal.ZERO : event.amount()) == 0)
                .findFirst();
        if (match.isEmpty()) {
            log.debug("disposition-failed {} sin colocación en vuelo que la reclame", event.dispositionId());
            return;
        }
        String reason = "RETURNED".equals(event.outcome())
                ? "El banco de la beneficiaria devolvió el SPEI (" + event.failureCode() + ")"
                : "El SPEI a la beneficiaria no se realizó (" + event.failureCode() + ")";
        lifecycle.fail(match.get().getPlacementId(), reason);
        log.warn("Colocación {} fallida por la disposición {}: {}", match.get().getPlacementId(),
                event.dispositionId(), reason);
    }

    /** What beneficiary reads of {@code credit-portfolio.disposition-failed}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DispositionFailedPayload(UUID dispositionId, UUID creditAccountId, UUID obligorPartyId,
                                           String dispositionType, BigDecimal amount, String outcome,
                                           String failureCode) {}
}

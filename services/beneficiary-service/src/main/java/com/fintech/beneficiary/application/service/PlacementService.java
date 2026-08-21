package com.fintech.beneficiary.application.service;

import com.fintech.beneficiary.application.CreatePlacementCommand;
import com.fintech.beneficiary.application.port.in.PlacementLifecycleUseCase;
import com.fintech.beneficiary.application.port.out.IdentityVerificationGateway;
import com.fintech.beneficiary.application.port.out.PlacementEventPublisher;
import com.fintech.beneficiary.application.port.out.PlacementRepository;
import com.fintech.beneficiary.application.port.out.PlacementTransitionRepository;
import com.fintech.beneficiary.domain.*;
import com.fintech.beneficiary.domain.event.PlacementApprovedEvent;
import com.fintech.beneficiary.domain.event.PlacementDisbursedEvent;
import com.fintech.beneficiary.domain.event.PlacementDisbursingEvent;
import com.fintech.beneficiary.domain.event.PlacementInvitedEvent;
import com.fintech.beneficiary.domain.event.PlacementStatusChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * Conduce la colocación por su máquina de estados.
 *
 * <p>Este servicio <b>no habla con nadie de afuera</b>. No llama a origination, ni a scoring, ni a
 * wallet: recibe hechos ya ocurridos y los aplica al agregado. Los orquestadores que sí llaman
 * hacia afuera —el KYC público, el listener de scoring, el de disposiciones— viven encima y usan
 * este servicio como su única puerta al estado. Así la máquina de estados se puede probar entera
 * sin un solo mock de HTTP, que es lo que la hace confiable.
 *
 * <p>Cada transición hace lo mismo, en este orden: carga, valida pertenencia si aplica, muta el
 * agregado (que valida la arista), guarda, escribe la bitácora y publica el evento. Publicar
 * después de guardar y dentro de la transacción es intencional: si el guardado falla, el evento no
 * sale; si el evento no sale, la transacción se cae y el estado no queda mentido.
 */
@Service
@Transactional
public class PlacementService implements PlacementLifecycleUseCase {

    private static final Logger log = LoggerFactory.getLogger(PlacementService.class);

    private final PlacementRepository placementRepository;
    private final PlacementTransitionRepository transitionRepository;
    private final PlacementEventPublisher eventPublisher;
    private final IdentityVerificationGateway verificationGateway;

    public PlacementService(PlacementRepository placementRepository,
                            PlacementTransitionRepository transitionRepository,
                            PlacementEventPublisher eventPublisher,
                            IdentityVerificationGateway verificationGateway) {
        this.placementRepository  = placementRepository;
        this.transitionRepository = transitionRepository;
        this.eventPublisher       = eventPublisher;
        this.verificationGateway  = verificationGateway;
    }

    @Override
    public Placement create(CreatePlacementCommand cmd) {
        if (placementRepository.existsLivePlacementFor(cmd.distributorPartyId(), cmd.beneficiaryPhone())) {
            throw new DuplicateLivePlacementException(cmd.beneficiaryPhone());
        }

        Placement placement = Placement.draft(
                cmd.distributorPartyId(), cmd.distributorCreditAccountId(),
                cmd.beneficiaryFullName(), cmd.beneficiaryPhone(), cmd.beneficiaryRelationship(),
                cmd.amount(), cmd.termFortnights(), cmd.fortnightlyPayment(),
                cmd.verificationMode(), cmd.inviteExpiresAt(),
                cmd.limits(), cmd.availableLine());

        placementRepository.save(placement);
        transitionRepository.save(PlacementTransition.of(
                placement.getPlacementId(), null, PlacementStatus.INVITED,
                PlacementTransition.Actor.DISTRIBUTOR, cmd.distributorPartyId(), null));

        eventPublisher.publish(new PlacementInvitedEvent(
                placement, placement.getInviteExpiresAt(), cmd.correlationId()));

        log.info("Colocación creada placementId={} distribuidor={} monto={} quincenas={} pago={} vence={}",
                placement.getPlacementId(), cmd.distributorPartyId(), cmd.amount(),
                cmd.termFortnights(), cmd.fortnightlyPayment(), placement.getInviteExpiresAt());
        return placement;
    }

    @Override
    @Transactional(readOnly = true)
    public Placement findById(UUID placementId) {
        return placementRepository.findById(placementId)
                .orElseThrow(() -> new PlacementNotFoundException(placementId));
    }

    @Override
    public Placement startKyc(UUID placementId) {
        return applyStatusChange(placementId, Placement::startKyc,
                PlacementTransition.Actor.BENEFICIARY, null);
    }

    /**
     * Cierra el KYC y decide qué pasa con la identidad.
     *
     * <p>Es aquí y no antes porque hasta ahora no había evidencia que evaluar: el expediente acaba
     * de formarse. Según la bandera, o se pregunta al proveedor o se manda derecho a la cola del
     * analista — y en automático, <b>cualquier tropiezo del proveedor también termina en la cola</b>.
     * El flujo no se rompe nunca; lo que cambia es cuánto trabajo humano cuesta.
     */
    @Override
    public Placement completeKyc(UUID placementId, UUID prospectId, UUID partyId, String clabe) {
        Placement placement = applyStatusChange(placementId,
                p -> p.completeKyc(prospectId, partyId, clabe),
                PlacementTransition.Actor.BENEFICIARY, null);

        var outcome = verificationGateway.evaluate(placementId, prospectId);
        placement.recordVerificationNotes(outcome.reasonSummary());

        if (outcome.requiresHumanReview()) {
            log.info("Identidad de {} a revisión manual: {}", placementId, outcome.reasonSummary());
        } else {
            // El proveedor resolvió dentro de sus umbrales. El veredicto se escribe con su nombre
            // como autor: un expediente tiene que poder decir que aquí no firmó una persona.
            placement.reviewIdentity(outcome.decision(), outcome.source(),
                    verificationGateway.describeStrategy(), null);
            transitionRepository.save(PlacementTransition.of(
                    placementId, placement.getStatus(), placement.getStatus(),
                    PlacementTransition.Actor.SYSTEM, null,
                    "Identidad " + outcome.decision() + " automáticamente — " + outcome.reasonSummary()));
            log.info("Identidad de {} verificada automáticamente", placementId);
        }

        placementRepository.save(placement);
        return placement;
    }

    @Override
    public Placement bureauReady(UUID placementId) {
        return applyStatusChange(placementId, Placement::bureauReady,
                PlacementTransition.Actor.SYSTEM, null);
    }

    @Override
    public Placement approve(UUID placementId, UUID distributorPartyId,
                             boolean riskAcknowledged, UUID riskAcknowledgementId) {
        Placement placement = loadOwnedBy(placementId, distributorPartyId);
        PlacementStatus from = placement.getStatus();

        placement.approve(riskAcknowledged);

        placementRepository.save(placement);
        recordTransition(placement, from, PlacementTransition.Actor.DISTRIBUTOR, distributorPartyId);
        eventPublisher.publish(new PlacementApprovedEvent(placement, riskAcknowledgementId, null));

        log.info("Colocación aprobada placementId={} distribuidor={} riskAck={}",
                placementId, distributorPartyId, riskAcknowledgementId);
        return placement;
    }

    @Override
    public Placement reject(UUID placementId, UUID distributorPartyId, String reason) {
        Placement placement = loadOwnedBy(placementId, distributorPartyId);
        return applyOn(placement, p -> p.reject(reason),
                PlacementTransition.Actor.DISTRIBUTOR, distributorPartyId);
    }

    @Override
    public Placement markDisbursing(UUID placementId, UUID dispositionId) {
        // No puede usar `applyStatusChange`: DISBURSING lleva el `dispositionId`, que es la única
        // llave que ata esta colocación con el movimiento de credit-portfolio. El evento genérico
        // no la transporta, y publicar sin ella dejaría al desembolso sin forma de correlacionarse.
        Placement placement = findById(placementId);
        PlacementStatus from = placement.getStatus();

        placement.markDisbursing(dispositionId);

        placementRepository.save(placement);
        recordTransition(placement, from, PlacementTransition.Actor.SYSTEM, null);
        eventPublisher.publish(new PlacementDisbursingEvent(placement, null));

        log.info("Colocación en desembolso placementId={} disposición={}", placementId, dispositionId);
        return placement;
    }

    @Override
    @Transactional
    public Placement reconcileDispositionId(UUID placementId, UUID dispositionId) {
        Placement placement = findById(placementId);
        if (dispositionId == null || dispositionId.equals(placement.getDispositionId())) {
            return placement;
        }
        UUID previo = placement.getDispositionId();
        placement.reconcileDispositionId(dispositionId);
        placementRepository.save(placement);
        // Sin transición ni evento: no cambió el ciclo de vida, se corrigió la llave. Se registra
        // porque un cambio de identificador que nadie ve es imposible de auditar después.
        log.info("Colocación {} correlacionada con la disposición {} (antes {})",
                placementId, dispositionId, previo);
        return placement;
    }

    public Placement markDisbursed(UUID placementId) {
        Placement placement = findById(placementId);
        PlacementStatus from = placement.getStatus();

        placement.markDisbursed();

        placementRepository.save(placement);
        recordTransition(placement, from, PlacementTransition.Actor.SYSTEM, null);
        eventPublisher.publish(new PlacementDisbursedEvent(placement, null));

        log.info("Colocación desembolsada placementId={} disposición={} beneficiaria={}",
                placementId, placement.getDispositionId(), placement.getBeneficiaryPartyId());
        return placement;
    }

    @Override
    public Placement reviewIdentity(UUID placementId, IdentityDecision decision,
                                    VerificationSource source, String decidedBy,
                                    String rejectionReason) {
        Placement placement = findById(placementId);

        placement.reviewIdentity(decision, source, decidedBy, rejectionReason);
        placementRepository.save(placement);

        // Queda en la bitácora aunque el estado de la colocación no se mueva: el dictamen es un
        // hecho del expediente y tiene que poder reconstruirse quién lo firmó y cuándo, igual que
        // cualquier transición.
        transitionRepository.save(PlacementTransition.of(
                placement.getPlacementId(), placement.getStatus(), placement.getStatus(),
                PlacementTransition.Actor.SYSTEM, null,
                "Identidad " + decision + " por " + decidedBy
                        + (rejectionReason == null ? "" : " — " + rejectionReason)));

        log.info("Identidad dictaminada placementId={} decisión={} por={} origen={}",
                placementId, decision, decidedBy, source);
        return placement;
    }

    @Override
    public Placement markPaidOff(UUID placementId) {
        return applyStatusChange(placementId, Placement::markPaidOff,
                PlacementTransition.Actor.SYSTEM, null);
    }

    @Override
    public Placement expire(UUID placementId) {
        return applyStatusChange(placementId, Placement::expire,
                PlacementTransition.Actor.SYSTEM, null);
    }

    @Override
    public Placement cancel(UUID placementId, UUID distributorPartyId, String reason) {
        Placement placement = loadOwnedBy(placementId, distributorPartyId);
        return applyOn(placement, p -> p.cancel(reason),
                PlacementTransition.Actor.DISTRIBUTOR, distributorPartyId);
    }

    @Override
    public Placement fail(UUID placementId, String reason) {
        return applyStatusChange(placementId, p -> p.fail(reason),
                PlacementTransition.Actor.SYSTEM, null);
    }

    // ── Plomería ─────────────────────────────────────────────────────────────────────────────

    private Placement applyStatusChange(UUID placementId, Consumer<Placement> mutation,
                                        PlacementTransition.Actor actor, UUID actorPartyId) {
        return applyOn(findById(placementId), mutation, actor, actorPartyId);
    }

    private Placement applyOn(Placement placement, Consumer<Placement> mutation,
                              PlacementTransition.Actor actor, UUID actorPartyId) {
        PlacementStatus from = placement.getStatus();

        mutation.accept(placement);

        placementRepository.save(placement);
        recordTransition(placement, from, actor, actorPartyId);
        eventPublisher.publish(new PlacementStatusChangedEvent(placement, from, null));

        log.info("Colocación placementId={} {} → {} por {}{}",
                placement.getPlacementId(), from, placement.getStatus(), actor,
                placement.getStatusReason() == null ? "" : " (" + placement.getStatusReason() + ")");
        return placement;
    }

    private void recordTransition(Placement placement, PlacementStatus from,
                                  PlacementTransition.Actor actor, UUID actorPartyId) {
        transitionRepository.save(PlacementTransition.of(
                placement.getPlacementId(), from, placement.getStatus(),
                actor, actorPartyId, placement.getStatusReason()));
    }

    private Placement loadOwnedBy(UUID placementId, UUID distributorPartyId) {
        Placement placement = findById(placementId);
        if (!placement.getDistributorPartyId().equals(distributorPartyId)) {
            throw new PlacementAccessDeniedException(placementId, distributorPartyId);
        }
        return placement;
    }
}

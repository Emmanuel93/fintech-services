package com.fintech.beneficiary.application.service;

import com.fintech.beneficiary.application.CreatePlacementCommand;
import com.fintech.beneficiary.application.port.out.IdentityVerificationGateway;
import com.fintech.beneficiary.application.port.out.PlacementEventPublisher;
import com.fintech.beneficiary.application.port.out.PlacementRepository;
import com.fintech.beneficiary.application.port.out.PlacementTransitionRepository;
import com.fintech.beneficiary.domain.*;
import com.fintech.beneficiary.domain.event.PlacementApprovedEvent;
import com.fintech.beneficiary.domain.event.PlacementDisbursedEvent;
import com.fintech.beneficiary.domain.event.PlacementEvent;
import com.fintech.beneficiary.domain.event.PlacementInvitedEvent;
import com.fintech.beneficiary.domain.event.PlacementStatusChangedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class PlacementServiceTest {

    private static final UUID DISTRIBUTOR = UUID.randomUUID();
    private static final UUID OTHER_DIST  = UUID.randomUUID();
    private static final UUID LINE        = UUID.randomUUID();
    private static final UUID PROSPECT    = UUID.randomUUID();
    private static final UUID PARTY       = UUID.randomUUID();
    private static final UUID DISPOSITION = UUID.randomUUID();
    private static final String CLABE     = "032180000118399999";
    private static final BigDecimal PAYMENT = new BigDecimal("868.06");
    private static final Instant EXPIRY     = Instant.now().plus(7, ChronoUnit.DAYS);
    /** Como los siembra `015-distributor-placement-limits.sql` para DL-DIST-STD-V1. */
    private static final PlacementLimits LIMITS = new PlacementLimits(
            new BigDecimal("5000"), new BigDecimal("60000"), 1000, 8, 16, 1);


    @Mock PlacementRepository placementRepository;
    @Mock PlacementTransitionRepository transitionRepository;
    @Mock PlacementEventPublisher eventPublisher;
    @Mock IdentityVerificationGateway verificationGateway;

    @Captor ArgumentCaptor<PlacementEvent> eventCaptor;
    @Captor ArgumentCaptor<PlacementTransition> transitionCaptor;

    PlacementService service;

    @BeforeEach
    void setUp() {
        service = new PlacementService(placementRepository, transitionRepository, eventPublisher,
                verificationGateway);
    }

    private static CreatePlacementCommand command() {
        return new CreatePlacementCommand(DISTRIBUTOR, LINE, "María Luisa Cortés Hernández",
                "5541829037", "Clienta de mi tienda desde 2023",
                new BigDecimal("18000"), 12, PAYMENT, VerificationMode.SELF_SERVICE_LINK, EXPIRY,
                LIMITS, new BigDecimal("205000"), "corr-1");
    }

    private Placement stored(PlacementStatus target) {
        Placement p = Placement.draft(DISTRIBUTOR, LINE, "María Luisa Cortés Hernández", "5541829037",
                null, new BigDecimal("18000"), 12, PAYMENT, VerificationMode.SELF_SERVICE_LINK, EXPIRY, LIMITS, null);
        switch (target) {
            case INVITED -> { }
            case KYC_IN_PROGRESS -> p.startKyc();
            case KYC_COMPLETED -> { p.startKyc(); p.completeKyc(PROSPECT, PARTY, CLABE); }
            case BUREAU_READY -> {
                p.startKyc(); p.completeKyc(PROSPECT, PARTY, CLABE); p.bureauReady();
                p.reviewIdentity(IdentityDecision.VERIFIED, VerificationSource.MANUAL, "analista-1", null);
            }
            case APPROVED -> {
                p.startKyc(); p.completeKyc(PROSPECT, PARTY, CLABE); p.bureauReady();
                p.reviewIdentity(IdentityDecision.VERIFIED, VerificationSource.MANUAL, "analista-1", null);
                p.approve(true);
            }
            case DISBURSING -> {
                p.startKyc(); p.completeKyc(PROSPECT, PARTY, CLABE); p.bureauReady();
                p.reviewIdentity(IdentityDecision.VERIFIED, VerificationSource.MANUAL, "analista-1", null);
                p.approve(true); p.markDisbursing(DISPOSITION);
            }
            case DISBURSED -> {
                p.startKyc(); p.completeKyc(PROSPECT, PARTY, CLABE); p.bureauReady();
                p.reviewIdentity(IdentityDecision.VERIFIED, VerificationSource.MANUAL, "analista-1", null);
                p.approve(true); p.markDisbursing(DISPOSITION); p.markDisbursed();
            }
            default -> throw new IllegalArgumentException("estado no soportado por el helper: " + target);
        }
        given(placementRepository.findById(p.getPlacementId())).willReturn(Optional.of(p));
        return p;
    }

    @Test
    @DisplayName("crear deja la colocación en INVITED, la registra en bitácora y anuncia la invitación")
    void createEmitsInvitedAndLogsTransition() {
        given(placementRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        Placement result = service.create(command());

        assertThat(result.getStatus()).isEqualTo(PlacementStatus.INVITED);
        assertThat(result.hasFile()).as("invitar no crea expediente").isFalse();

        then(eventPublisher).should().publish(eventCaptor.capture());
        assertThat(eventCaptor.getValue()).isInstanceOf(PlacementInvitedEvent.class);
        assertThat(eventCaptor.getValue().topic()).isEqualTo("beneficiary.placement-invited");

        then(transitionRepository).should().save(transitionCaptor.capture());
        PlacementTransition t = transitionCaptor.getValue();
        assertThat(t.getFromStatus()).isNull();
        assertThat(t.getToStatus()).isEqualTo(PlacementStatus.INVITED);
        assertThat(t.getActor()).isEqualTo(PlacementTransition.Actor.DISTRIBUTOR);
        assertThat(t.getActorPartyId()).isEqualTo(DISTRIBUTOR);
    }

    @Test
    @DisplayName("el evento de invitación no lleva el token — el secreto no viaja por Kafka")
    void invitedEventCarriesNoToken() {
        given(placementRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        service.create(command());

        then(eventPublisher).should().publish(eventCaptor.capture());
        PlacementInvitedEvent event = (PlacementInvitedEvent) eventCaptor.getValue();
        assertThat(event.getBeneficiaryPhone()).isEqualTo("5541829037");
        assertThat(event.getAmount()).isEqualByComparingTo("18000");
        // Ningún campo del evento es, ni puede derivarse en, el token de la liga.
        assertThat(event.getClass().getDeclaredFields())
                .noneMatch(f -> f.getName().toLowerCase().contains("token"));
    }

    @Test
    @DisplayName("cada transición publica al tópico que le toca")
    void eachTransitionPublishesToItsOwnTopic() {
        given(placementRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        Placement invited = stored(PlacementStatus.INVITED);
        service.startKyc(invited.getPlacementId());

        Placement inProgress = stored(PlacementStatus.KYC_IN_PROGRESS);
        given(verificationGateway.evaluate(any(), any()))
                .willReturn(IdentityVerificationGateway.VerificationOutcome
                        .requiresHumanReview("REVISION_MANUAL_CONFIGURADA"));
        service.completeKyc(inProgress.getPlacementId(), PROSPECT, PARTY, CLABE);

        Placement completed = stored(PlacementStatus.KYC_COMPLETED);
        service.bureauReady(completed.getPlacementId());

        then(eventPublisher).should(org.mockito.Mockito.times(3)).publish(eventCaptor.capture());
        assertThat(eventCaptor.getAllValues())
                .allMatch(PlacementStatusChangedEvent.class::isInstance)
                .extracting(PlacementEvent::topic)
                .containsExactly("beneficiary.kyc-started",
                                 "beneficiary.kyc-completed",
                                 "beneficiary.bureau-ready");
    }

    @Test
    @DisplayName("aprobar exige aceptación de riesgo y la evidencia viaja en el evento")
    void approvalCarriesTheRiskAcknowledgement() {
        given(placementRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        Placement p = stored(PlacementStatus.BUREAU_READY);
        UUID riskAckId = UUID.randomUUID();

        service.approve(p.getPlacementId(), DISTRIBUTOR, true, riskAckId);

        then(eventPublisher).should().publish(eventCaptor.capture());
        PlacementApprovedEvent event = (PlacementApprovedEvent) eventCaptor.getValue();
        assertThat(event.topic()).isEqualTo("beneficiary.placement-approved");
        assertThat(event.getRiskAcknowledgementId()).isEqualTo(riskAckId);
        assertThat(event.getBeneficiaryClabe()).isEqualTo(CLABE);
        assertThat(event.getBeneficiaryPartyId()).isEqualTo(PARTY);
        assertThat(event.getAmount()).isEqualByComparingTo("18000");
    }

    @Test
    @DisplayName("sin aceptación de riesgo no se aprueba, no se guarda y no se publica nada")
    void approvalWithoutRiskAckChangesNothing() {
        Placement p = stored(PlacementStatus.BUREAU_READY);

        assertThatThrownBy(() -> service.approve(p.getPlacementId(), DISTRIBUTOR, false, null))
                .isInstanceOf(PlacementValidationException.class);

        assertThat(p.getStatus()).isEqualTo(PlacementStatus.BUREAU_READY);
        then(placementRepository).should(org.mockito.Mockito.never()).save(any());
        then(eventPublisher).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("un distribuidor no puede aprobar la colocación de otro")
    void aDistributorCannotApproveSomeoneElsesPlacement() {
        Placement p = stored(PlacementStatus.BUREAU_READY);

        assertThatThrownBy(() -> service.approve(p.getPlacementId(), OTHER_DIST, true, UUID.randomUUID()))
                .isInstanceOf(PlacementAccessDeniedException.class);

        assertThat(p.getStatus()).isEqualTo(PlacementStatus.BUREAU_READY);
        then(eventPublisher).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("un distribuidor no puede revocar la colocación de otro")
    void aDistributorCannotCancelSomeoneElsesPlacement() {
        Placement p = stored(PlacementStatus.INVITED);

        assertThatThrownBy(() -> service.cancel(p.getPlacementId(), OTHER_DIST, "revocada"))
                .isInstanceOf(PlacementAccessDeniedException.class);

        assertThat(p.getStatus()).isEqualTo(PlacementStatus.INVITED);
    }

    @Test
    @DisplayName("el desembolso cierra el ciclo con su propio evento")
    void disbursementClosesTheCycle() {
        given(placementRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        Placement p = stored(PlacementStatus.DISBURSING);

        service.markDisbursed(p.getPlacementId());

        assertThat(p.getStatus()).isEqualTo(PlacementStatus.DISBURSED);
        then(eventPublisher).should().publish(eventCaptor.capture());
        PlacementDisbursedEvent event = (PlacementDisbursedEvent) eventCaptor.getValue();
        assertThat(event.topic()).isEqualTo("beneficiary.placement-disbursed");
        assertThat(event.getDispositionId()).isEqualTo(DISPOSITION);
        assertThat(event.getDisbursedAt()).isNotNull();
    }

    @Test
    @DisplayName("la disposición rechazada deja la colocación FAILED con el motivo, no APPROVED colgada")
    void rejectedDispositionFailsThePlacement() {
        given(placementRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        Placement p = stored(PlacementStatus.DISBURSING);

        service.fail(p.getPlacementId(), "Línea insuficiente");

        assertThat(p.getStatus()).isEqualTo(PlacementStatus.FAILED);
        assertThat(p.getStatusReason()).isEqualTo("Línea insuficiente");
        then(eventPublisher).should().publish(eventCaptor.capture());
        assertThat(eventCaptor.getValue().topic()).isEqualTo("beneficiary.placement-failed");
    }

    @Test
    @DisplayName("una transición imposible no guarda ni publica")
    void impossibleTransitionLeavesNoTrace() {
        Placement p = stored(PlacementStatus.INVITED);

        assertThatThrownBy(() -> service.bureauReady(p.getPlacementId()))
                .isInstanceOf(InvalidPlacementTransitionException.class);

        then(placementRepository).should(org.mockito.Mockito.never()).save(any());
        then(transitionRepository).shouldHaveNoInteractions();
        then(eventPublisher).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("buscar una colocación inexistente es 404 de dominio")
    void missingPlacementIsNotFound() {
        UUID unknown = UUID.randomUUID();
        given(placementRepository.findById(unknown)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.findById(unknown))
                .isInstanceOf(PlacementNotFoundException.class);
    }
}

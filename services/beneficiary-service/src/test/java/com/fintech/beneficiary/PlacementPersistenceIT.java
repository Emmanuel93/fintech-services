package com.fintech.beneficiary;

import com.fintech.beneficiary.application.CreatePlacementCommand;
import com.fintech.beneficiary.application.port.in.PlacementLifecycleUseCase;
import com.fintech.beneficiary.application.port.out.PlacementEventPublisher;
import com.fintech.beneficiary.application.port.out.PlacementTransitionRepository;
import com.fintech.beneficiary.domain.DuplicateLivePlacementException;
import com.fintech.beneficiary.domain.IdentityDecision;
import com.fintech.beneficiary.domain.VerificationSource;
import com.fintech.beneficiary.domain.Placement;
import com.fintech.beneficiary.domain.PlacementLimits;
import com.fintech.beneficiary.domain.PlacementStatus;
import com.fintech.beneficiary.domain.PlacementTransition;
import com.fintech.beneficiary.domain.VerificationMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Que el schema de Liquibase y el mapeo JPA digan lo mismo.
 *
 * <p>Con {@code ddl-auto: validate}, el solo hecho de que el contexto levante ya prueba que las
 * tablas, columnas y tipos coinciden — es el bug que más caro sale y el único que no se puede
 * cazar sin una base real. Encima se recorre el ciclo de vida completo contra Postgres, porque los
 * {@code CHECK} del changeset codifican invariantes del dominio (expediente todo-o-nada, disposición
 * obligatoria para desembolsar, {@code FAILED} siempre con motivo) y esos sólo se ejercen aquí.
 *
 * <p>Kafka se sustituye: la fase 1 no verifica entrega de eventos, sólo persistencia.
 */
@SpringBootTest
@ActiveProfiles("test")
class PlacementPersistenceIT {

    @Autowired PlacementLifecycleUseCase placements;
    @Autowired PlacementTransitionRepository transitions;

    @MockitoBean PlacementEventPublisher eventPublisher;

    private static final PlacementLimits LIMITS = new PlacementLimits(
            new BigDecimal("5000"), new BigDecimal("60000"), 1000, 8, 16, 1);

    private static CreatePlacementCommand command() {
        return new CreatePlacementCommand(UUID.randomUUID(), UUID.randomUUID(),
                "María Luisa Cortés Hernández", "5541829037", "Clienta de mi tienda desde 2023",
                new BigDecimal("18000"), 12, new BigDecimal("868.06"),
                VerificationMode.SELF_SERVICE_LINK, Instant.now().plus(7, ChronoUnit.DAYS),
                LIMITS, new BigDecimal("205000"), null);
    }

    @Test
    @DisplayName("el ciclo completo INVITED → PAID_OFF sobrevive a Postgres, con su bitácora")
    void fullLifecyclePersists() {
        Placement created = placements.create(command());
        UUID id = created.getPlacementId();

        assertThat(placements.findById(id).getStatus()).isEqualTo(PlacementStatus.INVITED);
        assertThat(placements.findById(id).hasFile()).isFalse();
        assertThat(placements.findById(id).getInviteExpiresAt()).isNotNull();
        assertThat(placements.findById(id).getFortnightlyPayment()).isEqualByComparingTo("868.06");

        placements.startKyc(id);
        placements.completeKyc(id, UUID.randomUUID(), UUID.randomUUID(), "032180000118399999");
        placements.bureauReady(id);
        placements.reviewIdentity(id, IdentityDecision.VERIFIED, VerificationSource.MANUAL,
                "analista-1", null);
        placements.approve(id, created.getDistributorPartyId(), true, UUID.randomUUID());
        placements.markDisbursing(id, UUID.randomUUID());
        placements.markDisbursed(id);
        placements.markPaidOff(id);

        Placement done = placements.findById(id);
        assertThat(done.getStatus()).isEqualTo(PlacementStatus.PAID_OFF);
        assertThat(done.getStatus().wireName()).isEqualTo("paidOff");
        assertThat(done.hasFile()).isTrue();
        assertThat(done.getDisbursedAt()).isNotNull();
        assertThat(done.getDecidedAt()).isNotNull();

        List<PlacementTransition> log = transitions.findByPlacementIdOrderByOccurredAtAsc(id);
        // El dictamen de identidad deja su propio renglón aunque no mueva el estado: es un hecho
        // del expediente y tiene que poder reconstruirse quién lo firmó, igual que una transición.
        // Por eso aparece un segundo BUREAU_READY→BUREAU_READY entre el buró y la aprobación.
        assertThat(log).extracting(PlacementTransition::getToStatus)
                .containsExactly(PlacementStatus.INVITED, PlacementStatus.KYC_IN_PROGRESS,
                        PlacementStatus.KYC_COMPLETED, PlacementStatus.BUREAU_READY,
                        PlacementStatus.BUREAU_READY,
                        PlacementStatus.APPROVED, PlacementStatus.DISBURSING,
                        PlacementStatus.DISBURSED, PlacementStatus.PAID_OFF);

        assertThat(log).anySatisfy(t ->
                assertThat(t.getReason()).contains("Identidad VERIFIED por analista-1"));
    }

    @Test
    @DisplayName("no se le manda una segunda liga al mismo número mientras la primera sigue viva")
    void secondLiveInviteToTheSamePhoneIsRejected() {
        CreatePlacementCommand first = command();
        placements.create(first);

        CreatePlacementCommand again = new CreatePlacementCommand(
                first.distributorPartyId(), first.distributorCreditAccountId(),
                first.beneficiaryFullName(), first.beneficiaryPhone(), first.beneficiaryRelationship(),
                first.amount(), first.termFortnights(), first.fortnightlyPayment(),
                first.verificationMode(), first.inviteExpiresAt(),
                first.limits(), first.availableLine(), null);

        assertThatThrownBy(() -> placements.create(again))
                .isInstanceOf(DuplicateLivePlacementException.class);
    }

    @Test
    @DisplayName("otro distribuidor sí puede colocarle a la misma persona — es negocio, no fraude")
    void anotherDistributorMayPlaceToTheSamePerson() {
        CreatePlacementCommand first = command();
        placements.create(first);

        CreatePlacementCommand fromSomeoneElse = new CreatePlacementCommand(
                UUID.randomUUID(), UUID.randomUUID(),
                first.beneficiaryFullName(), first.beneficiaryPhone(), first.beneficiaryRelationship(),
                first.amount(), first.termFortnights(), first.fortnightlyPayment(),
                first.verificationMode(), first.inviteExpiresAt(),
                first.limits(), first.availableLine(), null);

        assertThat(placements.create(fromSomeoneElse).getStatus()).isEqualTo(PlacementStatus.INVITED);
    }

    @Test
    @DisplayName("una colocación que vence guarda el motivo y no toca la línea")
    void expiredPlacementPersistsItsReason() {
        UUID id = placements.create(command()).getPlacementId();

        placements.expire(id);

        Placement expired = placements.findById(id);
        assertThat(expired.getStatus()).isEqualTo(PlacementStatus.EXPIRED);
        assertThat(expired.getStatusReason()).contains("venció");
        assertThat(expired.getStatus().consumesLine()).isFalse();
        assertThat(expired.hasFile()).as("una liga vencida no deja expediente").isFalse();
    }

    @Test
    @DisplayName("FAILED persiste siempre con motivo — el CHECK de la tabla lo exige igual que el dominio")
    void failedPlacementAlwaysCarriesItsReason() {
        UUID id = placements.create(command()).getPlacementId();
        placements.startKyc(id);

        placements.fail(id, "La prueba de vida no coincidió con la INE");

        Placement failed = placements.findById(id);
        assertThat(failed.getStatus()).isEqualTo(PlacementStatus.FAILED);
        assertThat(failed.getStatusReason()).isEqualTo("La prueba de vida no coincidió con la INE");
    }
}

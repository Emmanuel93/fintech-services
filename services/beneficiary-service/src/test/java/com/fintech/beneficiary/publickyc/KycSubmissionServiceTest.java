package com.fintech.beneficiary.publickyc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.beneficiary.application.port.in.PlacementLifecycleUseCase;
import com.fintech.beneficiary.application.port.out.*;
import com.fintech.beneficiary.application.service.GuestSessionGuard;
import com.fintech.beneficiary.application.service.KycSubmissionService;
import com.fintech.beneficiary.application.service.PublicKycService;
import com.fintech.beneficiary.domain.*;
import com.fintech.beneficiary.domain.event.BureauConsentGrantedEvent;
import com.fintech.beneficiary.domain.event.PlacementEvent;
import com.fintech.beneficiary.infrastructure.adapter.out.client.OriginationClient;
import com.fintech.beneficiary.infrastructure.adapter.out.client.PartyClient;
import com.fintech.beneficiary.infrastructure.config.BeneficiaryProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

/**
 * El cierre del KYC web: completitud, firma con el OTP e idempotencia del prospecto.
 *
 * <p>La garantía de un solo prospecto bajo concurrencia real la da el candado de Postgres, y por
 * eso se prueba en {@code PublicKycFlowIT} con hilos contra una base de verdad. Aquí se prueba la
 * otra mitad: que un reintento secuencial reuse el prospecto recordado.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KycSubmissionServiceTest {

    @Mock PlacementInviteRepository invites;
    @Mock KycSessionRepository sessions;
    @Mock PlacementRepository placements;
    @Mock KycDocumentRepository documents;
    @Mock ConsentRecordRepository consents;
    @Mock PlacementLifecycleUseCase lifecycle;
    @Mock PlacementEventPublisher events;
    @Mock OriginationClient origination;
    @Mock PartyClient party;
    @Mock BeneficiaryOtpSender otpSender;
    @Mock IneReaderPort ineReader;
    @Mock LivenessPort liveness;

    static final String RAW_SESSION = "sesion-de-invitado";
    static final String CODE = "123456";
    static final UUID PROSPECT = UUID.randomUUID();
    static final UUID PARTY = UUID.randomUUID();

    MutableClock clock;
    KycSubmissionService service;
    Placement placement;
    PlacementInvite invite;
    KycSession session;
    final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        clock = new MutableClock(Instant.now());
        BeneficiaryProperties props = new BeneficiaryProperties();
        GuestSessionGuard guard = new GuestSessionGuard(invites, sessions, placements, props, clock);
        PublicKycService publicKyc = new PublicKycService(guard, sessions, documents, consents, lifecycle,
                otpSender, ineReader, liveness, party, props, mapper, clock);
        service = new KycSubmissionService(guard, invites, sessions, documents, consents, lifecycle,
                events, origination, party, publicKyc, props);

        placement = KycFixtures.placement();
        placement.startKyc();
        invite = PlacementInvite.mint(placement.getPlacementId(), SecretHash.sha256Hex("liga"),
                placement.getInviteExpiresAt(), clock.instant());
        session = KycSession.open(placement.getPlacementId(), invite.getInviteId(),
                SecretHash.sha256Hex(RAW_SESSION), SecretHash.otpHash(invite.getInviteId(), CODE),
                clock.instant().plusSeconds(900), "201.141.1.1", "Mozilla", clock.instant());
        session.recordData(mapper.writeValueAsString(KycFixtures.data()), clock.instant());

        given(sessions.findInviteIdBySessionTokenHash(SecretHash.sha256Hex(RAW_SESSION)))
                .willReturn(Optional.of(invite.getInviteId()));
        given(sessions.findBySessionTokenHash(SecretHash.sha256Hex(RAW_SESSION))).willReturn(Optional.of(session));
        given(invites.lockById(invite.getInviteId())).willReturn(Optional.of(invite));
        given(lifecycle.findById(placement.getPlacementId())).willReturn(placement);
        given(lifecycle.bureauReady(placement.getPlacementId())).willAnswer(inv -> {
            placement.completeKyc(PROSPECT, PARTY, KycFixtures.CLABE);
            placement.bureauReady();
            return placement;
        });
        given(documents.summariesFor(placement.getPlacementId())).willReturn(allDocuments());
        given(consents.findByPlacementIdOrderByAcceptedAtAsc(placement.getPlacementId())).willReturn(allConsents());
        given(origination.createIndividualProspect(any(), anyString()))
                .willReturn(new OriginationClient.ProspectResponse(PROSPECT, KycFixtures.CURP, "+525541829037"));
        given(party.awaitByProspect(eq(PROSPECT), anyInt(), anyLong())).willReturn(Optional.of(
                new PartyClient.PartyResponse(PARTY, PROSPECT, "María Luisa", "Cortés", "Hernández",
                        "ACTIVE", "PENDING", null, null)));
    }

    private List<KycDocumentSummary> allDocuments() {
        List<KycDocumentSummary> list = new ArrayList<>();
        for (KycDocumentType t : KycDocumentType.values()) {
            list.add(new KycDocumentSummary(t, "image/jpeg", 10, clock.instant()));
        }
        return list;
    }

    private List<ConsentRecord> allConsents() {
        List<ConsentRecord> list = new ArrayList<>();
        for (ConsentType t : ConsentType.values()) {
            list.add(ConsentRecord.accept(placement.getPlacementId(), session.getSessionId(), t,
                    "2026-10", "201.141.1.1", "Mozilla", clock.instant()));
        }
        return list;
    }

    @Test
    @DisplayName("expediente completo: prospecto WEB, KYC cerrado, buró listo, constancia publicada, liga quemada")
    void happyPath() {
        var result = service.submit(RAW_SESSION, CODE);

        assertThat(result.status()).isEqualTo("bureauReady");
        assertThat(result.firstName()).isEqualTo("María");
        assertThat(result.phoneMask()).isEqualTo("55 •• •• 90 37");

        ArgumentCaptor<OriginationClient.NewProspect> prospect = ArgumentCaptor.forClass(OriginationClient.NewProspect.class);
        then(origination).should().createIndividualProspect(prospect.capture(), eq("WEB"));
        assertThat(prospect.getValue().gender()).isEqualTo("FEMALE");
        assertThat(prospect.getValue().stateOfBirth()).isEqualTo("Ciudad de México");
        assertThat(prospect.getValue().password()).as("aleatoria y fuerte").hasSizeGreaterThan(30);

        then(sessions).should().rememberProspect(session.getSessionId(), PROSPECT);
        then(lifecycle).should().completeKyc(placement.getPlacementId(), PROSPECT, PARTY, KycFixtures.CLABE);
        then(party).should().linkBeneficiary(placement.getDistributorPartyId(), PARTY);

        ArgumentCaptor<PlacementEvent> event = ArgumentCaptor.forClass(PlacementEvent.class);
        then(events).should().publish(event.capture());
        assertThat(event.getValue()).isInstanceOfSatisfying(BureauConsentGrantedEvent.class, e -> {
            assertThat(e.getConsentTextVersion()).isEqualTo("2026-10");
            assertThat(e.getIpAddress()).isEqualTo("201.141.1.1");
            assertThat(e.getOtpVerificationId()).isEqualTo(session.getSessionId());
            assertThat(e.getBeneficiaryProspectId()).isEqualTo(PROSPECT);
        });

        assertThat(invite.getRedeemedAt()).isNotNull();
        assertThat(session.isClosed()).isTrue();
    }

    @Test
    @DisplayName("sin selfie ni consentimientos: 409 EXPEDIENTE_INCOMPLETO con lo que falta, y nadie llama a origination")
    void incompleteFile() {
        given(documents.summariesFor(placement.getPlacementId())).willReturn(List.of(
                new KycDocumentSummary(KycDocumentType.INE_FRONT, "image/jpeg", 10, clock.instant()),
                new KycDocumentSummary(KycDocumentType.INE_BACK, "image/jpeg", 10, clock.instant())));
        given(consents.findByPlacementIdOrderByAcceptedAtAsc(any())).willReturn(List.of());

        assertThatThrownBy(() -> service.submit(RAW_SESSION, CODE))
                .isInstanceOfSatisfying(PublicKycException.IncompleteFile.class,
                        e -> assertThat(e.missing()).containsExactly("SELFIE", "CONSENTS"));
        then(origination).should(never()).createIndividualProspect(any(), anyString());
    }

    @Test
    @DisplayName("firmar con otro código: CODIGO_INCORRECTO, cuenta como intento y no crea nada")
    void wrongSignature() {
        assertThatThrownBy(() -> service.submit(RAW_SESSION, "654321"))
                .isInstanceOf(PublicKycException.WrongCode.class);
        assertThat(invite.getFailedAttempts()).isEqualTo(1);
        then(origination).should(never()).createIndividualProspect(any(), anyString());
    }

    @Test
    @DisplayName("si party tarda, el reintento REUSA el prospecto: origination no ve la CURP dos veces")
    void retryReusesTheProspect() {
        given(party.awaitByProspect(eq(PROSPECT), anyInt(), anyLong()))
                .willReturn(Optional.empty())
                .willReturn(Optional.of(new PartyClient.PartyResponse(PARTY, PROSPECT, "María Luisa",
                        "Cortés", "Hernández", "ACTIVE", "PENDING", null, null)));

        assertThatThrownBy(() -> service.submit(RAW_SESSION, CODE))
                .isInstanceOf(PublicKycException.DependencyUnavailable.class);
        assertThat(session.getProspectId()).isEqualTo(PROSPECT);
        assertThat(session.isClosed()).isFalse();

        assertThat(service.submit(RAW_SESSION, CODE).status()).isEqualTo("bureauReady");
        then(origination).should(times(1)).createIndividualProspect(any(), anyString());
    }

    @Test
    @DisplayName("un fallo al subir documentos a origination no tumba el cierre")
    void documentUploadIsBestEffort() {
        given(documents.findByPlacementId(placement.getPlacementId())).willReturn(List.of(
                KycDocument.of(placement.getPlacementId(), KycDocumentType.INE_FRONT, "image/jpeg",
                        new byte[]{1, 2, 3}, clock.instant())));
        org.mockito.BDDMockito.willThrow(new RuntimeException("origination caído"))
                .given(origination).uploadDocument(any(), anyString(), anyString(), anyString(), any());

        assertThat(service.submit(RAW_SESSION, CODE).status()).isEqualTo("bureauReady");
    }

    @Test
    @DisplayName("reenviar lo ya enviado devuelve el mismo resultado, sin un segundo expediente")
    void submitAfterSuccessIsIdempotent() {
        service.submit(RAW_SESSION, CODE);
        var again = service.submit(RAW_SESSION, CODE);

        assertThat(again.status()).isEqualTo("bureauReady");
        then(origination).should(times(1)).createIndividualProspect(any(), anyString());
        then(lifecycle).should(times(1)).completeKyc(any(), any(), any(), any());
    }

    @Test
    @DisplayName("sin sesión: SESION_EXPIRADA")
    void noSession() {
        assertThatThrownBy(() -> service.submit(null, CODE)).isInstanceOf(PublicKycException.SessionExpired.class);
        assertThatThrownBy(() -> service.submit("otra", CODE)).isInstanceOf(PublicKycException.SessionExpired.class);
    }
}

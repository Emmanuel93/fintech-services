package com.fintech.beneficiary.application.service;

import com.fintech.beneficiary.application.KycData;
import com.fintech.beneficiary.application.port.in.PlacementLifecycleUseCase;
import com.fintech.beneficiary.application.port.out.ConsentRecordRepository;
import com.fintech.beneficiary.application.port.out.KycDocumentRepository;
import com.fintech.beneficiary.application.port.out.KycSessionRepository;
import com.fintech.beneficiary.application.port.out.PlacementEventPublisher;
import com.fintech.beneficiary.application.port.out.PlacementInviteRepository;
import com.fintech.beneficiary.domain.*;
import com.fintech.beneficiary.domain.event.BureauConsentGrantedEvent;
import com.fintech.beneficiary.infrastructure.adapter.out.client.OriginationClient;
import com.fintech.beneficiary.infrastructure.adapter.out.client.PartyClient;
import com.fintech.beneficiary.infrastructure.config.BeneficiaryProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * El cierre del KYC web: de la captura a un expediente real.
 *
 * <p>Hace lo mismo que {@code KycSimulationController} —prospecto {@code INDIVIDUAL} en
 * origination, esperar el Party, cerrar el KYC, ligarla al distribuidor, dejarla lista para
 * decisión— pero con lo que ella capturó en vez de valores inventados, y además sube su INE y su
 * selfie al expediente y publica la constancia de buró.
 *
 * <p><b>Un solo prospecto por expediente, aunque se envíe dos veces.</b> Dos garantías juntas:
 *
 * <ol>
 *   <li>La fila de la liga se bloquea durante todo el cierre. Una segunda pestaña espera, y al
 *       entrar ve la sesión ya cerrada —o el prospecto ya recordado— y no crea otro.</li>
 *   <li>El {@code prospectId} se graba en la sesión <b>en su propia transacción</b>, en cuanto
 *       origination lo devuelve. Si después party tarda y el cierre se cae, el reintento reusa ese
 *       prospecto en vez de chocar con la CURP duplicada, que origination rechaza.</li>
 * </ol>
 *
 * <p>El precio del candado es tener una transacción abierta mientras se espera a party (unos
 * segundos, en el peor caso). Es una persona por colocación, no tráfico de app; se prefiere eso a
 * un expediente duplicado.
 *
 * <p>La identidad queda {@code PENDING}: no hay Incode, y la compara una persona en la mesa de KYC
 * contra las fotos que aquí se guardaron.
 */
@Service
public class KycSubmissionService {

    private static final Logger log = LoggerFactory.getLogger(KycSubmissionService.class);

    /** El canal con el que nace el prospecto: entró por la liga, desde su navegador. */
    static final String CHANNEL = "WEB";

    private final GuestSessionGuard guard;
    private final PlacementInviteRepository invites;
    private final KycSessionRepository sessions;
    private final KycDocumentRepository documents;
    private final ConsentRecordRepository consents;
    private final PlacementLifecycleUseCase lifecycle;
    private final PlacementEventPublisher eventPublisher;
    private final OriginationClient origination;
    private final PartyClient party;
    private final PublicKycService publicKyc;
    private final BeneficiaryProperties props;

    /** Cuánto se espera a que party consuma el {@code prospect-created}. Mismo valor que el simulador. */
    private static final int PARTY_AWAIT_ATTEMPTS = 15;
    private static final long PARTY_AWAIT_DELAY_MS = 400;

    public KycSubmissionService(GuestSessionGuard guard,
                                PlacementInviteRepository invites,
                                KycSessionRepository sessions,
                                KycDocumentRepository documents,
                                ConsentRecordRepository consents,
                                PlacementLifecycleUseCase lifecycle,
                                PlacementEventPublisher eventPublisher,
                                OriginationClient origination,
                                PartyClient party,
                                PublicKycService publicKyc,
                                BeneficiaryProperties props) {
        this.guard          = guard;
        this.invites        = invites;
        this.sessions       = sessions;
        this.documents      = documents;
        this.consents       = consents;
        this.lifecycle      = lifecycle;
        this.eventPublisher = eventPublisher;
        this.origination    = origination;
        this.party          = party;
        this.publicKyc      = publicKyc;
        this.props          = props;
    }

    /**
     * Firma y envía.
     *
     * <p>Sólo un código equivocado o el congelamiento confirman la transacción al lanzar —para que
     * el intento cuente—. Cualquier otro fallo la revierte entera: el expediente es todo o nada, y
     * lo único que sobrevive es el prospecto ya recordado.
     */
    @Transactional(noRollbackFor = {PublicKycException.WrongCode.class, PublicKycException.TooManyAttempts.class})
    public SubmitResult submit(String rawSessionToken, String code) {
        if (rawSessionToken == null || rawSessionToken.isBlank()) throw new PublicKycException.SessionExpired();
        String tokenHash = SecretHash.sha256Hex(rawSessionToken);

        // Primero el candado, después la lectura: leer la sesión antes de bloquear daría la foto
        // de antes de que otra pestaña terminara.
        UUID inviteId = sessions.findInviteIdBySessionTokenHash(tokenHash)
                .orElseThrow(PublicKycException.SessionExpired::new);
        PlacementInvite invite = invites.lockById(inviteId)
                .orElseThrow(PublicKycException.SessionExpired::new);
        KycSession session = guard.findAliveSession(rawSessionToken);
        Placement placement = lifecycle.findById(session.getPlacementId());
        Instant now = guard.now();
        var cfg = props.getPublicKyc();

        String candidate = SecretHash.otpHash(invite.getInviteId(), code == null ? "" : code.trim());

        // Ya se envió: un reintento —la red se cortó justo cuando respondimos— recibe la misma
        // respuesta, no un error. Pero sólo con el código correcto: la sesión cerrada no deja de
        // ser una credencial que alguien podría intentar reusar.
        if (session.isClosed()) {
            if (!SecretHash.matches(session.getVerifiedOtpHash(), candidate)) {
                throw new PublicKycException.WrongCode(invite.attemptsLeft(cfg.getMaxOtpFailures()));
            }
            return resultOf(placement);
        }
        if (!invite.isRedeemable(now) || !placement.inviteIsLive()) {
            throw new PublicKycException.SessionExpired();
        }
        if (invite.isFrozen(now)) {
            throw new PublicKycException.TooManyAttempts(
                    "Por seguridad, la liga está pausada. Intenta más tarde.",
                    Duration.between(now, invite.getFrozenUntil()).toSeconds());
        }

        // La firma: el mismo código con el que verificó su teléfono. Un código equivocado aquí
        // cuenta contra el mismo tope que al verificar — si no, la firma sería el lugar para
        // adivinar sin límite.
        if (!SecretHash.matches(session.getVerifiedOtpHash(), candidate)) {
            invite.registerFailure(now, cfg.getMaxOtpFailures(), Duration.ofMinutes(cfg.getFreezeMinutes()));
            throw new PublicKycException.WrongCode(invite.attemptsLeft(cfg.getMaxOtpFailures()));
        }

        List<String> missing = missingPieces(session);
        if (!missing.isEmpty()) throw new PublicKycException.IncompleteFile(missing);

        KycData data = publicKyc.readData(session);
        UUID placementId = placement.getPlacementId();

        // ── 1. El prospecto — y recordarlo antes de cualquier otra cosa ──────────────────────
        UUID prospectId = session.getProspectId();
        if (prospectId == null) {
            prospectId = createProspect(placement, data);
            sessions.rememberProspect(session.getSessionId(), prospectId);
            session.attachProspect(prospectId);
        } else {
            log.info("Reintento de cierre de la colocación {}: se reusa el prospecto {}", placementId, prospectId);
        }

        // ── 2. Sus archivos al expediente de origination — sin tumbar el cierre ──────────────
        uploadDocuments(placementId, prospectId);

        // ── 3. Su Party, que nace de un evento de Kafka ──────────────────────────────────────
        UUID beneficiaryPartyId = party.awaitByProspect(prospectId, PARTY_AWAIT_ATTEMPTS, PARTY_AWAIT_DELAY_MS)
                .map(PartyClient.PartyResponse::partyId)
                .orElseThrow(() -> new PublicKycException.DependencyUnavailable(
                        "Tu expediente quedó guardado pero aún no podemos cerrarlo. Intenta de nuevo en un minuto."));

        // ── 4. La colocación: expediente completo, ligada al distribuidor, lista para decidir ─
        if (placement.getStatus() == PlacementStatus.INVITED) {
            lifecycle.startKyc(placementId);
        }
        lifecycle.completeKyc(placementId, prospectId, beneficiaryPartyId, data.clabe());
        party.linkBeneficiary(placement.getDistributorPartyId(), beneficiaryPartyId);
        // El buró lo consulta scoring solo, al ver nacer el prospecto con el consentimiento
        // marcado. Marcar BUREAU_READY aquí es lo mismo que hace el simulador mientras no exista el
        // listener de la fase 4.
        Placement ready = lifecycle.bureauReady(placementId);

        // ── 5. La constancia regulatoria ─────────────────────────────────────────────────────
        ConsentRecord bureau = bureauAuthorizationOf(placementId);
        eventPublisher.publish(new BureauConsentGrantedEvent(
                placementId, placement.getDistributorPartyId(), bureau.getConsentId(),
                beneficiaryPartyId, prospectId, bureau.getTextVersion(), bureau.getIpAddress(),
                bureau.getUserAgent(), bureau.getSessionId(), bureau.getAcceptedAt(), null));

        // ── 6. La liga se quema: produjo su expediente (§3.4) ────────────────────────────────
        invite.redeem(now);
        session.close(now);
        sessions.save(session);

        log.info("KYC web cerrado: colocación {} prospecto {} party {} — identidad a revisión manual",
                placementId, prospectId, beneficiaryPartyId);
        return resultOf(ready);
    }

    /** Qué falta para poder firmar. Vacío = expediente completo. */
    List<String> missingPieces(KycSession session) {
        Set<KycDocumentType> docs = publicKyc.documentTypesOf(session.getPlacementId());
        List<String> missing = new ArrayList<>();
        for (KycDocumentType type : KycDocumentType.values()) {
            if (!docs.contains(type)) missing.add(type.name());
        }
        if (session.getCapturedData() == null) missing.add("DATA");
        if (!publicKyc.hasAllConsents(session.getPlacementId())) missing.add("CONSENTS");
        return missing;
    }

    private UUID createProspect(Placement placement, KycData d) {
        String email = d.email() != null ? d.email() : placement.getBeneficiaryPhone() + "@beneficiaria.mx";
        OriginationClient.NewProspect prospect = new OriginationClient.NewProspect(
                d.firstName(), d.lastName1(), d.lastName2(),
                d.curp(), d.rfc(), d.dateOfBirth(),
                Curp.genderOf(d.curp()),
                Curp.stateOfBirthOf(d.curp()).orElse("Nacido en el extranjero"),
                placement.getBeneficiaryPhone(), email,
                d.street(), d.exteriorNumber(), d.neighborhood(),
                d.municipality(), d.city(), d.state(), d.postalCode(),
                // Ella nunca inicia sesión (§3.2): la contraseña existe porque origination todavía la
                // exige, es aleatoria y no la conoce nadie. Una fija sería una cuenta con contraseña
                // conocida por el sistema.
                "Kb9#" + SecretHash.randomToken());
        try {
            var created = origination.createIndividualProspect(prospect, CHANNEL);
            if (created == null || created.prospectId() == null) {
                throw new IllegalStateException("origination no devolvió prospectId");
            }
            return created.prospectId();
        } catch (RuntimeException e) {
            // El detalle va al log, no a la pantalla: es una página pública y el mensaje de un
            // servicio interno no es asunto de quien la usa.
            log.error("origination rechazó el prospecto de la colocación {}: {}",
                    placement.getPlacementId(), e.getMessage());
            throw new PublicKycException.DependencyUnavailable(
                    "No pudimos registrar tu expediente en este momento. Intenta de nuevo en unos minutos.");
        }
    }

    /**
     * Mejor esfuerzo: si origination no recibe un archivo, el cierre sigue. La evidencia que mira
     * la mesa de KYC es la de este servicio, que ya quedó guardada; lo que se pierde es la copia en
     * el expediente de la persona, y eso se nota en un WARN, no en una beneficiaria atorada.
     */
    private void uploadDocuments(UUID placementId, UUID prospectId) {
        for (KycDocument doc : documents.findByPlacementId(placementId)) {
            try {
                origination.uploadDocument(prospectId, doc.getDocumentType().name(),
                        fileNameOf(doc), doc.getContentType(), doc.getContent());
            } catch (RuntimeException e) {
                log.warn("No se pudo subir {} de la colocación {} al prospecto {}: {}",
                        doc.getDocumentType(), placementId, prospectId, e.getMessage());
            }
        }
    }

    private static String fileNameOf(KycDocument doc) {
        String type = doc.getContentType() == null ? "" : doc.getContentType();
        String ext = type.equals("application/pdf") ? "pdf"
                : type.equals("image/png") ? "png"
                : type.equals("image/webp") ? "webp"
                : "jpg";
        return doc.getDocumentType().name().toLowerCase(Locale.ROOT) + "." + ext;
    }

    private ConsentRecord bureauAuthorizationOf(UUID placementId) {
        return consents.findByPlacementIdOrderByAcceptedAtAsc(placementId).stream()
                .filter(c -> c.getConsentType() == ConsentType.BUREAU_AUTHORIZATION)
                .reduce((first, second) -> second)
                .orElseThrow(() -> new PublicKycException.IncompleteFile(List.of("CONSENTS")));
    }

    private static SubmitResult resultOf(Placement placement) {
        return new SubmitResult(placement.getStatus().wireName(),
                PublicKycService.firstNameOf(placement.getBeneficiaryFullName()),
                PhoneMask.of(placement.getBeneficiaryPhone()));
    }

    public record SubmitResult(String status, String firstName, String phoneMask) {}
}

package com.fintech.beneficiary.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.beneficiary.application.KycData;
import com.fintech.beneficiary.application.port.in.PlacementLifecycleUseCase;
import com.fintech.beneficiary.application.port.out.BeneficiaryOtpSender;
import com.fintech.beneficiary.application.port.out.ConsentRecordRepository;
import com.fintech.beneficiary.application.port.out.IneReaderPort;
import com.fintech.beneficiary.application.port.out.KycDocumentRepository;
import com.fintech.beneficiary.application.port.out.KycSessionRepository;
import com.fintech.beneficiary.application.port.out.LivenessPort;
import com.fintech.beneficiary.domain.*;
import com.fintech.beneficiary.infrastructure.adapter.out.client.PartyClient;
import com.fintech.beneficiary.infrastructure.config.BeneficiaryProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * La web pública de KYC, paso por paso — todo menos el cierre, que vive en
 * {@link KycSubmissionService}.
 *
 * <p>Es la contraparte real de {@code KycSimulationController}: lo que el simulador inventa del lado
 * del servidor, aquí lo captura ella desde su teléfono —su OTP, las dos caras de su INE, su selfie,
 * sus datos y sus tres consentimientos—.
 *
 * <p><b>Los errores del dominio de la liga no deshacen la transacción</b>
 * ({@code noRollbackFor}). Un código equivocado lanza, y si eso revirtiera el contador de intentos,
 * el tope de 5 no existiría: se podría adivinar indefinidamente.
 */
@Service
public class PublicKycService {

    private static final Logger log = LoggerFactory.getLogger(PublicKycService.class);

    /** «Hola, María»: el nombre que ella reconoce y nada más. Sin apellidos antes del OTP. */
    private static final String DISTRIBUTOR_FALLBACK = "Tu distribuidora";

    private final GuestSessionGuard guard;
    private final KycSessionRepository sessions;
    private final KycDocumentRepository documents;
    private final ConsentRecordRepository consents;
    private final PlacementLifecycleUseCase lifecycle;
    private final BeneficiaryOtpSender otpSender;
    private final IneReaderPort ineReader;
    private final LivenessPort liveness;
    private final PartyClient party;
    private final BeneficiaryProperties props;
    private final ObjectMapper objectMapper;
    private final KycDataValidator validator;

    public PublicKycService(GuestSessionGuard guard,
                            KycSessionRepository sessions,
                            KycDocumentRepository documents,
                            ConsentRecordRepository consents,
                            PlacementLifecycleUseCase lifecycle,
                            BeneficiaryOtpSender otpSender,
                            IneReaderPort ineReader,
                            LivenessPort liveness,
                            PartyClient party,
                            BeneficiaryProperties props,
                            ObjectMapper objectMapper,
                            Clock clock) {
        this.guard        = guard;
        this.sessions     = sessions;
        this.documents    = documents;
        this.consents     = consents;
        this.lifecycle    = lifecycle;
        this.otpSender    = otpSender;
        this.ineReader    = ineReader;
        this.liveness     = liveness;
        this.party        = party;
        this.props        = props;
        this.objectMapper = objectMapper;
        this.validator    = new KycDataValidator(clock);
    }

    // ── Portada — sólo con el token de la liga ───────────────────────────────────────────────

    /**
     * Lo que la portada puede decir <b>antes</b> del OTP: quién le presta, cuánto, a qué plazo y
     * a qué número le llegará el código. Ningún dato de ella más allá de su primer nombre: la liga
     * puede reenviarse, y quien la abra sin ser ella no debe enterarse de nada que no sepa ya.
     */
    @Transactional(readOnly = true)
    public InviteView viewInvite(String rawToken) {
        PlacementInvite invite = guard.findInvite(rawToken);
        Placement placement = guard.requireLivePlacement(invite);

        BigDecimal total = placement.getFortnightlyPayment()
                .multiply(BigDecimal.valueOf(placement.getTermFortnights()));
        return new InviteView(
                lenderNameOf(placement),
                firstNameOf(placement.getBeneficiaryFullName()),
                placement.getAmount(),
                placement.getFortnightlyPayment(),
                placement.getTermFortnights(),
                total,
                PhoneMask.of(placement.getBeneficiaryPhone()),
                placement.getStatus().wireName());
    }

    /**
     * Le manda un código. Hay uno vigente a la vez: pedir otro invalida el anterior.
     *
     * <p>La fila de la liga se bloquea para que dos toques simultáneos en «Enviar código» cuenten
     * como dos envíos y no se pisen el contador.
     */
    @Transactional(noRollbackFor = PublicKycException.class)
    public OtpSent sendOtp(String rawToken) {
        PlacementInvite invite = guard.lockInvite(rawToken);
        Placement placement = guard.requireLivePlacement(invite);
        Instant now = guard.now();
        var cfg = props.getPublicKyc();

        boolean dev = cfg.hasDevOtpCode();
        String code = dev ? cfg.getDevOtpCode().trim() : SecretHash.randomOtpCode();
        invite.registerOtpSend(SecretHash.otpHash(invite.getInviteId(), code),
                now.plus(cfg.getOtpTtlMinutes(), ChronoUnit.MINUTES), now,
                cfg.getMaxOtpSends(), Duration.ofMinutes(cfg.getOtpSendWindowMinutes()));

        otpSender.send(placement.getBeneficiaryPhone(), code, dev);
        return new OtpSent(true, PhoneMask.of(placement.getBeneficiaryPhone()), cfg.getResendInSeconds());
    }

    /**
     * Comprueba el código y acuña la sesión de invitado.
     *
     * <p>Bloquea la liga: si dos pestañas verifican el mismo código a la vez, la primera lo consume
     * y la segunda ya no lo encuentra. Un código abre <b>una</b> sesión.
     *
     * <p>Mueve la colocación a {@code KYC_IN_PROGRESS} la primera vez; las siguientes —reanudar
     * dentro de los 7 días— ya está ahí y no se toca.
     */
    @Transactional(noRollbackFor = PublicKycException.class)
    public GuestSession verifyOtp(String rawToken, String code, String ip, String userAgent) {
        PlacementInvite invite = guard.lockInvite(rawToken);
        Placement placement = guard.requireLivePlacement(invite);
        Instant now = guard.now();
        var cfg = props.getPublicKyc();

        String candidate = SecretHash.otpHash(invite.getInviteId(), code == null ? "" : code.trim());
        String verifiedHash = invite.verifyOtp(candidate, now,
                cfg.getMaxOtpFailures(), Duration.ofMinutes(cfg.getFreezeMinutes()));

        // Reanudar: la sesión nueva hereda lo capturado y el prospecto de la anterior, y la
        // anterior se cierra. Una sesión viva a la vez por colocación.
        Optional<KycSession> previous = sessions.findFirstByPlacementIdOrderByCreatedAtDesc(placement.getPlacementId());
        sessions.closeOpenSessions(placement.getPlacementId(), now);

        String rawSession = SecretHash.randomToken();
        KycSession session = KycSession.open(placement.getPlacementId(), invite.getInviteId(),
                SecretHash.sha256Hex(rawSession), verifiedHash, guard.sessionExpiry(invite),
                ip, userAgent, now);
        session.resumeFrom(previous.orElse(null));
        sessions.save(session);

        if (placement.getStatus() == PlacementStatus.INVITED) {
            lifecycle.startKyc(placement.getPlacementId());
        }
        log.info("Sesión de invitado abierta para la colocación {} (sesión {})",
                placement.getPlacementId(), session.getSessionId());
        return new GuestSession(rawSession, session.getExpiresAt());
    }

    // ── Pasos — con el token de sesión ───────────────────────────────────────────────────────

    /** Dónde va. La web lo usa para retomar en el paso correcto al reabrir la liga. */
    @Transactional
    public SessionState state(String rawSessionToken) {
        KycSession session = guard.findAliveSession(rawSessionToken);
        Set<KycDocumentType> docs = documentTypesOf(session.getPlacementId());
        boolean consentsAccepted = hasAllConsents(session.getPlacementId());
        KycData data = readData(session);

        if (session.isClosed()) {
            return new SessionState("DONE", sorted(docs), data, consentsAccepted);
        }
        // Una sesión abierta que ya no puede avanzar —liga revocada, colocación cancelada— se
        // trata como vencida, igual que en los demás pasos.
        guard.requireOpenSession(rawSessionToken);

        String step;
        if (!docs.contains(KycDocumentType.INE_FRONT) || !docs.contains(KycDocumentType.INE_BACK)) step = "INE";
        else if (!docs.contains(KycDocumentType.SELFIE)) step = "LIVENESS";
        else if (data == null) step = "DATA";
        else if (!consentsAccepted) step = "CONSENTS";
        else step = "SUBMIT";
        return new SessionState(step, sorted(docs), data, consentsAccepted);
    }

    /**
     * Las dos caras de la INE. Se guardan y se leen; la lectura precarga «Tus datos», que ella
     * revisa. Si vuelve a tomar las fotos, reemplazan a las anteriores.
     */
    @Transactional(noRollbackFor = PublicKycException.class)
    public IneReaderPort.Extraction uploadIne(String rawSessionToken, Upload front, Upload back) {
        var open = guard.requireOpenSession(rawSessionToken);
        Map<String, String> errors = new LinkedHashMap<>();
        checkFile(errors, "front", front, true);
        checkFile(errors, "back", back, true);
        if (!errors.isEmpty()) throw new PublicKycException.InvalidData(errors);

        UUID placementId = open.placement().getPlacementId();
        store(placementId, KycDocumentType.INE_FRONT, front);
        store(placementId, KycDocumentType.INE_BACK, back);

        return ineReader.read(new IneReaderPort.Request(
                front.content(), front.contentType(), back.content(), back.contentType(),
                open.placement().getBeneficiaryFullName(), open.placement().getBeneficiaryPhone()));
    }

    @Transactional(noRollbackFor = PublicKycException.class)
    public LivenessPort.Result uploadSelfie(String rawSessionToken, Upload selfie) {
        var open = guard.requireOpenSession(rawSessionToken);
        Map<String, String> errors = new LinkedHashMap<>();
        // La selfie es una foto: un PDF aquí no es una prueba de vida de nadie.
        checkFile(errors, "selfie", selfie, false);
        if (!errors.isEmpty()) throw new PublicKycException.InvalidData(errors);

        store(open.placement().getPlacementId(), KycDocumentType.SELFIE, selfie);
        return liveness.capture(selfie.content(), selfie.contentType());
    }

    /** «Tus datos». Se valida todo de una vez y se guarda normalizado. */
    @Transactional(noRollbackFor = PublicKycException.class)
    public void saveData(String rawSessionToken, KycData raw) {
        var open = guard.requireOpenSession(rawSessionToken);
        KycData clean = validator.validate(raw);
        try {
            open.session().recordData(objectMapper.writeValueAsString(clean), guard.now());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("No se pudieron serializar los datos capturados", e);
        }
        sessions.save(open.session());
    }

    /**
     * Los tres consentimientos, cada uno con su renglón: IP, navegador, hora y versión del texto.
     *
     * <p>Los tres o ninguno. Aceptar el aviso de privacidad pero no la consulta de buró no es un
     * estado intermedio válido: sin autorización no hay buró, y sin buró la distribuidora no tiene
     * con qué decidir. Repetir la llamada no duplica: la constancia ya existe.
     */
    @Transactional(noRollbackFor = PublicKycException.class)
    public void acceptConsents(String rawSessionToken, ConsentsCommand cmd, String ip, String userAgent) {
        var open = guard.requireOpenSession(rawSessionToken);
        Map<String, String> errors = new LinkedHashMap<>();
        if (cmd == null || !Boolean.TRUE.equals(cmd.privacyNotice())) {
            errors.put("privacyNotice", "Debes aceptar el aviso de privacidad");
        }
        if (cmd == null || !Boolean.TRUE.equals(cmd.bureauAuthorization())) {
            errors.put("bureauAuthorization", "Debes autorizar la consulta de tu historial crediticio");
        }
        if (cmd == null || !Boolean.TRUE.equals(cmd.creditOwnership())) {
            errors.put("creditOwnership", "Debes confirmar que el crédito es tuyo");
        }
        if (cmd == null || cmd.textVersion() == null || cmd.textVersion().isBlank()) {
            errors.put("textVersion", "Falta la versión del texto aceptado");
        }
        if (!errors.isEmpty()) throw new PublicKycException.InvalidData(errors);

        KycSession session = open.session();
        Set<ConsentType> already = EnumSet.noneOf(ConsentType.class);
        consents.findBySessionId(session.getSessionId()).forEach(c -> already.add(c.getConsentType()));

        Instant now = guard.now();
        for (ConsentType type : ConsentType.values()) {
            if (already.contains(type)) continue;
            consents.save(ConsentRecord.accept(open.placement().getPlacementId(),
                    session.getSessionId(), type, cmd.textVersion(), ip, userAgent, now));
        }
        log.info("Consentimientos registrados para la colocación {} (texto {})",
                open.placement().getPlacementId(), cmd.textVersion());
    }

    // ── Plomería ─────────────────────────────────────────────────────────────────────────────

    /** El tope por archivo. Lo repite el multipart de Spring, pero aquí se dice con el campo. */
    static final long MAX_FILE_BYTES = 8L * 1024 * 1024;

    private static void checkFile(Map<String, String> errors, String field, Upload file, boolean pdfAllowed) {
        if (file == null || file.content() == null || file.content().length == 0) {
            errors.put(field, "Falta el archivo");
            return;
        }
        if (file.content().length > MAX_FILE_BYTES) {
            errors.put(field, "El archivo pesa más de 8 MB");
            return;
        }
        String type = file.contentType() == null ? "" : file.contentType().toLowerCase(Locale.ROOT);
        boolean ok = type.startsWith("image/") || (pdfAllowed && type.equals("application/pdf"));
        if (!ok) {
            errors.put(field, pdfAllowed ? "Debe ser una foto o un PDF" : "Debe ser una foto");
        }
    }

    private void store(UUID placementId, KycDocumentType type, Upload file) {
        Instant now = guard.now();
        KycDocument doc = documents.findByPlacementIdAndDocumentType(placementId, type)
                .map(existing -> {
                    existing.replaceWith(file.contentType(), file.content(), now);
                    return existing;
                })
                .orElseGet(() -> KycDocument.of(placementId, type, file.contentType(), file.content(), now));
        documents.save(doc);
    }

    Set<KycDocumentType> documentTypesOf(UUID placementId) {
        Set<KycDocumentType> types = EnumSet.noneOf(KycDocumentType.class);
        documents.summariesFor(placementId).forEach(d -> types.add(d.documentType()));
        return types;
    }

    /** Los consentimientos cuelgan de la colocación: los de una sesión anterior también cuentan. */
    boolean hasAllConsents(UUID placementId) {
        Set<ConsentType> types = EnumSet.noneOf(ConsentType.class);
        consents.findByPlacementIdOrderByAcceptedAtAsc(placementId).forEach(c -> types.add(c.getConsentType()));
        return types.containsAll(EnumSet.allOf(ConsentType.class));
    }

    KycData readData(KycSession session) {
        if (session.getCapturedData() == null) return null;
        try {
            return objectMapper.readValue(session.getCapturedData(), KycData.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Datos capturados ilegibles en la sesión " + session.getSessionId(), e);
        }
    }

    private static List<String> sorted(Set<KycDocumentType> docs) {
        return docs.stream().sorted().map(Enum::name).toList();
    }

    /**
     * El nombre de la distribuidora. El id que guarda la colocación es el de su <b>prospecto</b>
     * —el {@code sub} de su JWT—, así que se resuelve por ahí. Si party no contesta, la portada
     * sigue: «Tu distribuidora» es menos cálido pero no le impide a nadie continuar.
     */
    private String lenderNameOf(Placement placement) {
        try {
            return party.findByProspect(placement.getDistributorPartyId())
                    .map(PartyClient.PartyResponse::fullName)
                    .filter(n -> !n.isBlank())
                    .orElse(DISTRIBUTOR_FALLBACK);
        } catch (RuntimeException e) {
            log.warn("No se pudo resolver el nombre de la distribuidora {}: {}",
                    placement.getDistributorPartyId(), e.getMessage());
            return DISTRIBUTOR_FALLBACK;
        }
    }

    static String firstNameOf(String fullName) {
        if (fullName == null || fullName.isBlank()) return "";
        return fullName.trim().split("\\s+")[0];
    }

    // ── Vistas ───────────────────────────────────────────────────────────────────────────────

    public record InviteView(String lenderName, String beneficiaryFirstName, BigDecimal amount,
                             BigDecimal fortnightlyPayment, int termFortnights, BigDecimal totalToPay,
                             String phoneMask, String status) {}

    public record OtpSent(boolean sent, String phoneMask, int resendInSeconds) {}

    public record GuestSession(String sessionToken, Instant expiresAt) {}

    public record SessionState(String step, List<String> documents, KycData data, boolean consentsAccepted) {}

    public record Upload(byte[] content, String contentType, String fileName) {}

    public record ConsentsCommand(Boolean privacyNotice, Boolean bureauAuthorization,
                                  Boolean creditOwnership, String textVersion) {}
}

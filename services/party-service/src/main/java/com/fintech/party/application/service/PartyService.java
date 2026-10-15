package com.fintech.party.application.service;

import com.fintech.party.application.port.out.KycVerificationRepository;
import com.fintech.party.application.port.out.PartyEventPublisher;
import com.fintech.party.application.port.out.PartyRepository;
import com.fintech.party.domain.KycVerification;
import com.fintech.party.domain.Party;
import com.fintech.party.domain.PartyNotFoundException;
import com.fintech.party.domain.PartyStatus;
import com.fintech.party.domain.PartyType;
import com.fintech.party.infrastructure.adapter.in.messaging.ProspectCreatedPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class PartyService {

    private static final Logger log = LoggerFactory.getLogger(PartyService.class);

    private final PartyRepository partyRepository;
    private final KycVerificationRepository kycRepository;
    private final PartyEventPublisher eventPublisher;

    public PartyService(PartyRepository partyRepository,
                        KycVerificationRepository kycRepository,
                        PartyEventPublisher eventPublisher) {
        this.partyRepository = partyRepository;
        this.kycRepository   = kycRepository;
        this.eventPublisher  = eventPublisher;
    }

    /**
     * Crea un Party con estado PROSPECT a partir del evento de creación de prospecto.
     * Idempotente: si ya existe un Party para el mismo prospectId, hace skip silencioso.
     */
    @Transactional
    public Party createFromProspect(ProspectCreatedPayload payload) {
        if (partyRepository.existsByProspectId(payload.prospectId())) {
            log.info("Party already exists for prospectId={} — skipping", payload.prospectId());
            return partyRepository.findByProspectId(payload.prospectId()).orElseThrow();
        }

        PartyType partyType = partyTypeOf(payload.prospectType(), payload.prospectId());

        Party party = Party.create(
                UUID.randomUUID(),
                payload.prospectId(),
                partyType,
                payload.firstName(),
                payload.lastName1(),
                payload.lastName2(),
                payload.curp(),
                payload.rfc(),
                payload.dateOfBirth()
        );

        Party saved = partyRepository.save(party);
        log.info("Party created partyId={} prospectId={} type={} status={}",
                saved.getPartyId(), saved.getProspectId(),
                saved.getPartyType(), saved.getStatus());
        return saved;
    }

    /**
     * Traduce el tipo de prospecto al tipo de persona, <b>sin reventar</b>.
     *
     * <h2>Lo que esto arregla</h2>
     *
     * Antes era {@code PartyType.valueOf(payload.prospectType())} directo, y los dos catálogos no
     * coinciden: {@code ProspectType} tiene {@code DISTRIBUTOR} y {@code PartyType} sólo
     * {@code INDIVIDUAL} y {@code BUSINESS}. Un prospecto con ese valor tumbaba el listener con un
     * {@code IllegalArgumentException} — y este servicio corre <b>sin DLT</b>, así que el mensaje se
     * reintentaba en bucle y la persona nunca se creaba. No ocurría todavía sólo porque ningún
     * productor emitía ese valor; el endpoint que lo acepta es {@code permitAll()}.
     *
     * <h2>Por qué DISTRIBUTOR se traduce a INDIVIDUAL y no a BUSINESS</h2>
     *
     * Porque un distribuidor <b>es una persona física con actividad empresarial</b>: eso es una
     * persona, no una empresa. Y porque el invariante del modelo (I-03) dice que un distribuidor es
     * <b>party + rol</b>, no un tipo de persona distinto — el rol se concede aparte y es ahí donde
     * vive esa condición. Mapearlo a {@code BUSINESS} metería a una persona física en el carril de
     * las morales, que es un error más difícil de ver que un crash.
     *
     * <h2>Y por qué un valor desconocido también entra como INDIVIDUAL</h2>
     *
     * Porque la alternativa es no crear la persona, y no crearla es peor: el alta ya ocurrió del
     * otro lado, el prospecto existe, y dejar a party sin su fila deja a la persona sin ficha, sin
     * veredicto de verificación y por tanto bloqueada en toda compuerta, sin que nadie sepa por qué.
     * Se crea con el tipo más conservador y se grita en el log, que es revisable.
     */
    private PartyType partyTypeOf(String prospectType, UUID prospectId) {
        if (prospectType == null || prospectType.isBlank()) {
            log.warn("El prospecto {} llegó sin tipo — se crea como INDIVIDUAL", prospectId);
            return PartyType.INDIVIDUAL;
        }
        try {
            return PartyType.valueOf(prospectType);
        } catch (IllegalArgumentException e) {
            log.warn("El prospecto {} llegó con prospectType='{}', que PartyType no tiene. Se crea "
                     + "como INDIVIDUAL: un distribuidor es persona física con actividad "
                     + "empresarial, y su condición es un ROL, no un tipo de persona (I-03).",
                    prospectId, prospectType);
            return PartyType.INDIVIDUAL;
        }
    }

    /**
     * Agrega o actualiza una verificación KYC para un party.
     * Si ya existe una verificación activa del mismo tipo, la sobreescribe con el nuevo estado.
     */
    @Transactional
    public KycVerification addKycVerification(UUID partyId, String documentType,
                                              String verificationStatus, String verifiedBy,
                                              String documentRef, LocalDate expiresAt,
                                              String rejectionReason) {
        partyRepository.findById(partyId)
                .orElseThrow(() -> new PartyNotFoundException(partyId));

        KycVerification verification = kycRepository
                .findLatestByPartyIdAndDocumentType(partyId, documentType)
                .orElseGet(() -> KycVerification.create(partyId, documentType));

        if ("VERIFIED".equals(verificationStatus)) {
            verification.verify(verifiedBy, documentRef, expiresAt);
        } else if ("REJECTED".equals(verificationStatus)) {
            verification.reject(rejectionReason);
        }

        KycVerification saved = kycRepository.save(verification);
        log.info("KycVerification saved verificationId={} partyId={} documentType={} status={}",
                saved.getVerificationId(), partyId, documentType, verificationStatus);
        return saved;
    }

    /**
     * Blacklistea un party y publica el evento PartyBlacklisted.
     * Lanza PartyNotFoundException si el party no existe.
     * Lanza IllegalStateException si el party ya está BLACKLISTED.
     */
    @Transactional
    public Party blacklistParty(UUID partyId, String reason, String sourceList) {
        Party party = partyRepository.findById(partyId)
                .orElseThrow(() -> new PartyNotFoundException(partyId));

        party.blacklist(reason);
        Party saved = partyRepository.save(party);

        try {
            eventPublisher.publishPartyBlacklisted(partyId, reason, sourceList);
        } catch (Exception e) {
            log.error("Failed to publish PartyBlacklisted for partyId={} — blacklist persisted", partyId, e);
        }

        log.info("Party blacklisted partyId={} reason={}", partyId, reason);
        return saved;
    }

    /** Captura/actualiza el perfil fiscal CFDI del party y lo propaga a Facturación. */
    @Transactional
    public Party updateFiscalProfile(UUID partyId, String taxName, String taxRegime,
                                     String taxZipCode, String cfdiUse) {
        Party party = partyRepository.findById(partyId)
                .orElseThrow(() -> new PartyNotFoundException(partyId));

        party.updateFiscalProfile(taxName, taxRegime, taxZipCode, cfdiUse);
        Party saved = partyRepository.save(party);

        try {
            eventPublisher.publishFiscalProfileUpdated(partyId, saved.getProspectId(), saved.getPartyType().name(),
                    saved.getRfc(), taxName, taxRegime, taxZipCode, cfdiUse);
        } catch (Exception e) {
            log.error("Failed to publish FiscalProfileUpdated for partyId={} — profile persisted", partyId, e);
        }

        log.info("Party fiscal profile updated partyId={} regime={}", partyId, taxRegime);
        return saved;
    }

    public Optional<Party> findById(UUID partyId) {
        return partyRepository.findById(partyId);
    }

    public Optional<Party> findByProspectId(UUID prospectId) {
        return partyRepository.findByProspectId(prospectId);
    }

    /** Búsqueda paginada del backoffice (por nombre/CURP/RFC, tipo, estado y ejecutivo). */
    public Page<Party> search(String q, PartyType type, PartyStatus status, UUID executiveId, Pageable pageable) {
        return partyRepository.search(q, type, status, executiveId, pageable);
    }

    /** Asigna (o reasigna) el ejecutivo de cuenta de un cliente. */
    @Transactional
    public Party assignExecutive(UUID partyId, UUID executiveId, String executiveName) {
        Party party = partyRepository.findById(partyId)
                .orElseThrow(() -> new PartyNotFoundException(partyId));
        party.assignExecutive(executiveId, executiveName);
        Party saved = partyRepository.save(party);
        log.info("Party {} assigned to executive {} ({})", partyId, executiveId, executiveName);

        // Se publica el HECHO, no una petición de aviso: party no sabe quién va a reaccionar.
        //
        // Después de guardar y sin atarlo al resultado: si la publicación falla, el cliente ya
        // quedó asignado, que es lo que importa. Un hecho no publicado se nota mucho menos que una
        // asignación perdida, y hacer que la transacción dependa del bus le añadiría a la operación
        // un modo de fallo a cambio de nada.
        try {
            eventPublisher.publishExecutiveAssigned(partyId, executiveId, executiveName, nameOf(saved));
        } catch (RuntimeException ex) {
            // El envío es asíncrono y sus fallos llegan por callback, pero un error al construir o
            // serializar el mensaje sí revienta aquí. Sin este catch, el comentario de arriba sería
            // una promesa que el código no cumple — y lo destapó la prueba que lo afirmaba.
            log.error("La asignación quedó hecha pero el hecho no se pudo publicar partyId={} executiveId={}",
                    partyId, executiveId, ex);
        }
        return saved;
    }

    /** Hidratación por lote: varios parties por id en una sola consulta (evita N+1). */
    public List<Party> findByIds(Collection<UUID> ids) {
        return partyRepository.findByPartyIdIn(ids);
    }

    /** Hidratación por lote por prospectId (cartera guarda el prospecto como obligado). */
    public List<Party> findByProspectIds(Collection<UUID> prospectIds) {
        return partyRepository.findByProspectIdIn(prospectIds);
    }

    public List<KycVerification> findKycVerifications(UUID partyId) {
        return kycRepository.findAllByPartyId(partyId);
    }

    /** El nombre con el que el ejecutivo reconoce al cliente en su campana. */
    private static String nameOf(Party p) {
        String name = java.util.stream.Stream.of(p.getFirstName(), p.getLastName1(), p.getLastName2())
                .filter(x -> x != null && !x.isBlank())
                .reduce((a, b) -> a + " " + b)
                .orElse(null);
        // Una persona moral no tiene nombre de pila: su razón social vive en `taxName`.
        return name != null ? name : p.getTaxName();
    }

    /**
     * Guarda el veredicto de verificación que emitió compliance.
     *
     * <p><b>Si el Party todavía no existe, no se pierde: se ignora y se recupera solo.</b> Los dos
     * —la creación del Party y la evaluación— cuelgan del mismo evento de alta, así que el orden no
     * está garantizado. Cuando el Party se crea, arranca en `PENDING`, que bloquea; y compliance
     * vuelve a publicar en cuanto el veredicto cambia —que ocurre siempre, porque la identidad
     * empieza en revisión y alguien tiene que firmarla—. El lado seguro del desorden es quedarse
     * bloqueado, no pasar.
     */
    @Transactional
    public void applyVerification(UUID prospectId, String identityStatus,
                                  String screeningStatus, String blockReason) {
        partyRepository.findByProspectId(prospectId).ifPresentOrElse(
                party -> {
                    party.applyVerification(identityStatus, screeningStatus, blockReason);
                    partyRepository.save(party);
                },
                () -> log.info("Veredicto para prospectId={} sin Party todavía — se reaplicará "
                               + "con la siguiente publicación; mientras tanto queda PENDING", prospectId));
    }
}

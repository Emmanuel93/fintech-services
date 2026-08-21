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

        PartyType partyType = PartyType.valueOf(payload.prospectType());

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
}

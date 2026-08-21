package com.fintech.origination.application.service;

import com.fintech.origination.application.ApplyScoringDecisionCommand;
import com.fintech.origination.application.RecordApprovalDecisionCommand;
import com.fintech.origination.application.RequestDocumentsCommand;
import com.fintech.origination.application.StartCreditApplicationCommand;
import com.fintech.origination.application.StartCreditApplicationResult;
import com.fintech.origination.application.port.in.ApplyScoringDecisionUseCase;
import com.fintech.origination.application.port.in.FindCreditApplicationUseCase;
import com.fintech.origination.application.port.in.RecordApprovalDecisionUseCase;
import com.fintech.origination.application.port.in.RequestDocumentsUseCase;
import com.fintech.origination.application.port.in.StartCreditApplicationUseCase;
import com.fintech.origination.application.port.out.ApplicationEventPublisher;
import com.fintech.origination.application.port.out.CreditApplicationRepository;
import com.fintech.origination.application.port.out.PromoterResolver;
import com.fintech.origination.application.port.out.ProspectRepository;
import com.fintech.origination.application.port.out.ScoreRequestPublisher;
import com.fintech.origination.domain.ApplicationStatus;
import com.fintech.origination.domain.CooldownActiveException;
import com.fintech.origination.domain.CreditApplication;
import com.fintech.origination.domain.CreditApplicationNotFoundException;
import com.fintech.origination.domain.DuplicateActiveApplicationException;
import com.fintech.origination.domain.ProductType;
import com.fintech.origination.domain.PromoterCodeNotResolvableException;
import com.fintech.origination.domain.Prospect;
import com.fintech.origination.domain.ProspectNotFoundException;
import com.fintech.origination.domain.event.ApplicationApprovedEvent;
import com.fintech.origination.domain.event.ApplicationRejectedEvent;
import com.fintech.origination.domain.event.DocumentsRequestedEvent;
import com.fintech.origination.domain.event.ScoreRequestedEvent;
import com.fintech.origination.infrastructure.config.OriginationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
public class CreditApplicationService
        implements StartCreditApplicationUseCase, FindCreditApplicationUseCase,
                   ApplyScoringDecisionUseCase, RecordApprovalDecisionUseCase,
                   RequestDocumentsUseCase {

    private static final Logger log = LoggerFactory.getLogger(CreditApplicationService.class);

    private final CreditApplicationRepository applicationRepository;
    private final ProspectRepository prospectRepository;
    private final ScoreRequestPublisher scoreRequestPublisher;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final PromoterResolver promoterResolver;
    private final OriginationProperties properties;

    public CreditApplicationService(CreditApplicationRepository applicationRepository,
                                     ProspectRepository prospectRepository,
                                     ScoreRequestPublisher scoreRequestPublisher,
                                     ApplicationEventPublisher applicationEventPublisher,
                                     PromoterResolver promoterResolver,
                                     OriginationProperties properties) {
        this.applicationRepository    = applicationRepository;
        this.prospectRepository       = prospectRepository;
        this.scoreRequestPublisher    = scoreRequestPublisher;
        this.applicationEventPublisher = applicationEventPublisher;
        this.promoterResolver         = promoterResolver;
        this.properties               = properties;
    }

    @Override
    public StartCreditApplicationResult start(StartCreditApplicationCommand cmd) {
        log.info("Starting credit application prospectId={} productType={}",
                cmd.prospectId(), cmd.productType());

        Prospect prospect = prospectRepository.findById(cmd.prospectId())
                .orElseThrow(() -> new ProspectNotFoundException(cmd.prospectId().toString()));

        // OA-03: one active application per (prospectId, productType)
        if (applicationRepository.existsActiveByProspectIdAndProductType(
                cmd.prospectId(), cmd.productType())) {
            log.warn("Duplicate active application prospectId={} productType={}",
                    cmd.prospectId(), cmd.productType());
            throw new DuplicateActiveApplicationException(cmd.productType());
        }

        // UW-06: cooldown after rejection
        Instant cooldownSince = Instant.now().minus(properties.getCooldownDays(), ChronoUnit.DAYS);
        applicationRepository.findMostRecentRejectionAfter(
                cmd.prospectId(), cmd.productType(), cooldownSince).ifPresent(rejected -> {
            log.warn("Cooldown active prospectId={} productType={} rejectedAt={}",
                    cmd.prospectId(), cmd.productType(), rejected.getRejectedAt());
            throw new CooldownActiveException(cmd.productType().name(), properties.getCooldownDays());
        });

        CreditApplication application = CreditApplication.start(
                prospect.getProspectId(),
                prospect.getProspectType(),
                cmd.productType(),
                cmd.requestedAmount(),
                cmd.requestedTerm(),
                resolvePromoter(cmd.promoterCode()));

        CreditApplication saved = applicationRepository.save(application);
        log.info("Credit application persisted applicationId={} status={}",
                saved.getApplicationId(), saved.getStatus());

        scoreRequestPublisher.publish(new ScoreRequestedEvent(
                saved.getApplicationId(),
                saved.getProspectId(),
                saved.getProspectType(),
                saved.getProductType(),
                saved.getRequestedAmount(),
                saved.getRequestedTerm(),
                cmd.correlationId()));

        return new StartCreditApplicationResult.ApplicationStarted(
                saved.getApplicationId(),
                saved.getProspectId(),
                saved.getProductType(),
                saved.getStatus(),
                saved.getCreatedAt());
    }

    /**
     * Resuelve el promoterCode a un partyId de distribuidor. Si ya es un UUID, se usa tal cual
     * (compatibilidad). Si es un código humano (p.ej. DIST0001), se resuelve contra sales-org; si no
     * existe, se RECHAZA la solicitud —CM-07: mejor fallar que originar un crédito sin comisión—. Sin
     * promoterCode, null (crédito directo, sin distribuidor).
     */
    private String resolvePromoter(String promoterCode) {
        if (promoterCode == null || promoterCode.isBlank()) {
            return null;
        }
        String code = promoterCode.trim();
        try {
            UUID.fromString(code);
            return code; // ya es un partyId — se propaga tal cual
        } catch (IllegalArgumentException notUuid) {
            return promoterResolver.resolveDistributor(code)
                    .map(UUID::toString)
                    .orElseThrow(() -> new PromoterCodeNotResolvableException(code));
        }
    }

    @Override
    @Transactional(readOnly = true)
    public CreditApplication getById(UUID applicationId) {
        return applicationRepository.findById(applicationId)
                .orElseThrow(() -> new CreditApplicationNotFoundException(applicationId.toString()));
    }

    @Override
    @Transactional(readOnly = true)
    public List<CreditApplication> findByProspectId(UUID prospectId) {
        return applicationRepository.findByProspectId(prospectId);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<CreditApplication> search(Collection<ApplicationStatus> statuses,
                                          ProductType productType,
                                          Collection<ProductType> productTypes,
                                          UUID prospectId,
                                          Instant from,
                                          Instant to,
                                          String q,
                                          Pageable pageable) {
        return applicationRepository.search(statuses, productType, productTypes, prospectId,
                from, to, q, pageable);
    }

    // ── Phase C — apply scoring decision ────────────────────────────────────────

    @Override
    public void apply(ApplyScoringDecisionCommand cmd) {
        Optional<CreditApplication> match = (cmd.applicationId() != null)
                ? applicationRepository.findById(cmd.applicationId())
                        .filter(a -> !a.getStatus().isTerminal())
                : applicationRepository.findActiveByProspectIdAndProductType(cmd.prospectId(), cmd.productType());

        if (match.isEmpty()) {
            log.info("No active application (applicationId={} prospectId={} productType={}) — decision {} ignored",
                    cmd.applicationId(), cmd.prospectId(), cmd.productType(), cmd.decision());
            return;
        }

        CreditApplication app = match.get();
        log.info("Applying scoring decision applicationId={} decision={} risk={}",
                app.getApplicationId(), cmd.decision(), cmd.riskLevel());

        app.linkScoreEvaluation(cmd.evaluationId());

        switch (cmd.decision()) {
            case "AUTO_APPROVED" -> {
                app.approve(cmd.riskLevel(), cmd.decision());
                applicationRepository.save(app);
                publishApproved(app);
            }
            case "MANUAL_REVIEW" -> {
                // Route to COMMITTEE when amount exceeds threshold (or committee_threshold config)
                boolean isCommittee = app.getRequestedAmount() != null
                        && app.getRequestedAmount().compareTo(properties.getCommitteeAmountThreshold()) > 0;
                if (isCommittee) {
                    app.sendToCommitteeReview(cmd.riskLevel(), cmd.decision());
                    log.info("Application routed to COMMITTEE_REVIEW applicationId={} amount={}",
                            app.getApplicationId(), app.getRequestedAmount());
                } else {
                    app.sendToManualReview(cmd.riskLevel(), cmd.decision());
                    log.info("Application routed to UNDER_MANUAL_REVIEW applicationId={}",
                            app.getApplicationId());
                }
                applicationRepository.save(app);
            }
            case "REJECTED" -> {
                app.reject(
                        "Solicitud rechazada por evaluación de riesgo crediticio (nivel " + cmd.riskLevel() + ")",
                        cmd.riskLevel());
                applicationRepository.save(app);
                publishRejected(app);
            }
            default -> log.warn("Unknown scoring decision '{}' for applicationId={} — no transition applied",
                    cmd.decision(), app.getApplicationId());
        }
    }

    // ── Phase H — manual / committee decision ────────────────────────────────────

    @Override
    public void record(RecordApprovalDecisionCommand cmd) {
        CreditApplication app = applicationRepository.findById(cmd.applicationId())
                .orElseThrow(() -> new CreditApplicationNotFoundException(cmd.applicationId().toString()));

        if (cmd.approved()) {
            app.recordManualApproval(cmd.decidedBy());
            applicationRepository.save(app);
            log.info("Application manually APPROVED applicationId={} decidedBy={}",
                    app.getApplicationId(), cmd.decidedBy());
            publishApproved(app);
        } else {
            app.recordManualRejection(cmd.decidedBy(), cmd.rejectionReason());
            applicationRepository.save(app);
            log.info("Application manually REJECTED applicationId={} decidedBy={} reason={}",
                    app.getApplicationId(), cmd.decidedBy(), cmd.rejectionReason());
            publishRejected(app);
        }
    }

    // ── E6 — documentos pendientes ──────────────────────────────────────────────

    @Override
    public void requestDocuments(RequestDocumentsCommand cmd) {
        CreditApplication app = applicationRepository.findById(cmd.applicationId())
                .orElseThrow(() -> new CreditApplicationNotFoundException(cmd.applicationId().toString()));

        Instant deadline = Instant.now().plus(properties.getDocumentsTtlDays(), ChronoUnit.DAYS);
        app.requestDocuments(cmd.requestedBy(), cmd.note(), deadline);
        applicationRepository.save(app);
        log.info("Documents requested applicationId={} by={} deadline={}",
                app.getApplicationId(), cmd.requestedBy(), deadline);

        applicationEventPublisher.publishDocumentsRequested(new DocumentsRequestedEvent(
                app.getApplicationId(), app.getProspectId(), app.getProductType().name(),
                cmd.requestedBy(), cmd.note(), deadline, cmd.correlationId()));
    }

    @Override
    public void markDocumentsReceived(UUID applicationId) {
        CreditApplication app = applicationRepository.findById(applicationId)
                .orElseThrow(() -> new CreditApplicationNotFoundException(applicationId.toString()));
        app.resumeAfterDocuments();
        applicationRepository.save(app);
        log.info("Documents received applicationId={} — back to manual review", applicationId);
    }

    // ── Internal event publishing ────────────────────────────────────────────────

    private void publishApproved(CreditApplication app) {
        applicationEventPublisher.publishApplicationApproved(new ApplicationApprovedEvent(
                app.getApplicationId(), app.getProspectId(),
                app.getProductType().name(), app.getApprovalFlow(), app.getDecidedBy()));
    }

    private void publishRejected(CreditApplication app) {
        applicationEventPublisher.publishApplicationRejected(new ApplicationRejectedEvent(
                app.getApplicationId(), app.getProspectId(),
                app.getProductType().name(), app.getApprovalFlow(), app.getDecidedBy(),
                app.getRejectionReason()));
    }
}

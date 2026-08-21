package com.fintech.collections.application.service;

import com.fintech.collections.application.CollectionsProperties;
import com.fintech.collections.application.ProposeAgreementCommand;
import com.fintech.collections.application.port.in.AgreementUseCase;
import com.fintech.collections.application.port.out.AccountBalanceSnapshotRepository;
import com.fintech.collections.application.port.out.CollectionAgreementRepository;
import com.fintech.collections.application.port.out.CollectionCaseRepository;
import com.fintech.collections.application.port.out.CollectionsEventPublisher;
import com.fintech.collections.domain.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** AG-*: reestructura / quita parcial — bilateral agreements requiring debtor acceptance + internal authorization. */
@Service
@Transactional
public class AgreementService implements AgreementUseCase {

    private static final Logger log = LoggerFactory.getLogger(AgreementService.class);

    private final CollectionCaseRepository caseRepository;
    private final CollectionAgreementRepository agreementRepository;
    private final AccountBalanceSnapshotRepository snapshotRepository;
    private final BureauReportingService bureauReportingService;
    private final CollectionsEventPublisher eventPublisher;
    private final CollectionsProperties properties;

    public AgreementService(CollectionCaseRepository caseRepository,
                             CollectionAgreementRepository agreementRepository,
                             AccountBalanceSnapshotRepository snapshotRepository,
                             BureauReportingService bureauReportingService,
                             CollectionsEventPublisher eventPublisher,
                             CollectionsProperties properties) {
        this.caseRepository         = caseRepository;
        this.agreementRepository    = agreementRepository;
        this.snapshotRepository     = snapshotRepository;
        this.bureauReportingService = bureauReportingService;
        this.eventPublisher         = eventPublisher;
        this.properties             = properties;
    }

    @Override
    public CollectionAgreement propose(ProposeAgreementCommand cmd) {
        CollectionCase collectionCase = caseRepository.findById(cmd.caseId())
                .orElseThrow(() -> new CollectionCaseNotFoundException(cmd.caseId().toString()));

        // AG-07: only offered once a case is actively managed — not on a freshly opened OPEN case
        if (collectionCase.getStatus() != CaseStatus.MANAGED && collectionCase.getStatus() != CaseStatus.LEGAL) {
            throw new InvalidCaseStateException("Agreements can only be proposed for MANAGED or LEGAL cases, was "
                    + collectionCase.getStatus());
        }
        // AG-01: only one PROPOSED/ACCEPTED agreement per case at a time
        agreementRepository.findActiveByCaseId(cmd.caseId()).ifPresent(a -> {
            throw new InvalidCaseStateException("Case " + cmd.caseId() + " already has an active agreement: " + a.getAgreementId());
        });

        BigDecimal originalDebt = snapshotRepository.findById(collectionCase.getCreditAccountId())
                .map(s -> s.getTotalDebt())
                .orElse(collectionCase.getTotalDebt());

        CollectionAgreement agreement = switch (cmd.type()) {
            case RESTRUCTURE -> {
                if (cmd.newTerms() == null || cmd.newTerms().newTermMonths() == null) {
                    throw new InvalidAgreementStateException("RESTRUCTURE requires newTerms.newTermMonths");
                }
                if (cmd.newTerms().newTermMonths() > properties.getMaxTermExtensionMonths()) {
                    throw new InvalidAgreementStateException("newTermMonths " + cmd.newTerms().newTermMonths()
                            + " exceeds max_term_extension_months=" + properties.getMaxTermExtensionMonths());
                }
                yield CollectionAgreement.proposeRestructure(cmd.caseId(), collectionCase.getCreditAccountId(),
                        collectionCase.getObligorPartyId(), originalDebt, cmd.newTerms());
            }
            case QUITA_PARCIAL -> {
                if (cmd.forgivenAmount() == null) {
                    throw new InvalidAgreementStateException("QUITA_PARCIAL requires forgivenAmount");
                }
                yield CollectionAgreement.proposeQuitaParcial(cmd.caseId(), collectionCase.getCreditAccountId(),
                        collectionCase.getObligorPartyId(), originalDebt, cmd.forgivenAmount(),
                        properties.getMaxForgivenessPct());
            }
        };

        agreementRepository.save(agreement);
        log.info("CollectionAgreement proposed agreementId={} caseId={} type={}",
                agreement.getAgreementId(), cmd.caseId(), cmd.type());
        eventPublisher.publishCollectionAgreementProposed(agreement);
        return agreement;
    }

    @Override
    public CollectionAgreement accept(UUID agreementId) {
        CollectionAgreement agreement = findOrThrow(agreementId);
        agreement.accept();
        agreementRepository.save(agreement);
        log.info("CollectionAgreement accepted agreementId={}", agreementId);
        return agreement;
    }

    @Override
    public CollectionAgreement reject(UUID agreementId) {
        CollectionAgreement agreement = findOrThrow(agreementId);
        agreement.reject();
        agreementRepository.save(agreement);
        log.info("CollectionAgreement rejected agreementId={}", agreementId);
        return agreement;
    }

    /** AG-02: requires explicit authorization — no agreement executes automatically. */
    @Override
    public CollectionAgreement authorize(UUID agreementId, String authorizedBy, String authorizationRef) {
        CollectionAgreement agreement = findOrThrow(agreementId);
        agreement.execute(authorizedBy, authorizationRef);
        agreementRepository.save(agreement);
        log.info("CollectionAgreement executed agreementId={} type={} authorizedBy={}",
                agreementId, agreement.getType(), authorizedBy);

        // AG-03/AG-04: credit-portfolio applies the new terms / balance reduction
        eventPublisher.publishCollectionAgreementExecuted(agreement);

        // AG-06: QUITA_PARCIAL requires bureau reporting, same as write-off
        if (agreement.requiresBureauReport()) {
            bureauReportingService.createPendingReport(agreement.getCreditAccountId(), agreement.getObligorPartyId(),
                    BureauEventType.QUITA_PARCIAL, agreement.getAgreementId(), agreement.getForgivenAmount());
        }
        return agreement;
    }

    /** AG-05: no response within agreement_response_days -> EXPIRED. */
    public void expireStaleProposals() {
        Instant cutoff = Instant.now().minusSeconds(properties.getAgreementResponseDays() * 86400L);
        for (CollectionAgreement agreement : agreementRepository.findProposedBefore(cutoff)) {
            agreement.expire();
            agreementRepository.save(agreement);
            log.info("CollectionAgreement expired agreementId={}", agreement.getAgreementId());
        }
    }

    private CollectionAgreement findOrThrow(UUID agreementId) {
        return agreementRepository.findById(agreementId)
                .orElseThrow(() -> new AgreementNotFoundException(agreementId.toString()));
    }
}

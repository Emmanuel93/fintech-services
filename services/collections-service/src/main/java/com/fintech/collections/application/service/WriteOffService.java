package com.fintech.collections.application.service;

import com.fintech.collections.application.ApproveWriteOffCommand;
import com.fintech.collections.application.CollectionsProperties;
import com.fintech.collections.application.RequestWriteOffCommand;
import com.fintech.collections.application.port.in.WriteOffUseCase;
import com.fintech.collections.application.port.out.AccountBalanceSnapshotRepository;
import com.fintech.collections.application.port.out.CollectionCaseRepository;
import com.fintech.collections.application.port.out.CollectionsEventPublisher;
import com.fintech.collections.application.port.out.WriteOffRecordRepository;
import com.fintech.collections.domain.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * WO-*: quita total. Two-step: request() only publishes intent (WO-01, no persistence);
 * approve() is the one authorized step that creates the immutable WriteOffRecord (WO-02).
 */
@Service
@Transactional
public class WriteOffService implements WriteOffUseCase {

    private static final Logger log = LoggerFactory.getLogger(WriteOffService.class);

    private final CollectionCaseRepository caseRepository;
    private final WriteOffRecordRepository writeOffRepository;
    private final AccountBalanceSnapshotRepository snapshotRepository;
    private final BureauReportingService bureauReportingService;
    private final CollectionsEventPublisher eventPublisher;
    private final CollectionsProperties properties;

    public WriteOffService(CollectionCaseRepository caseRepository,
                            WriteOffRecordRepository writeOffRepository,
                            AccountBalanceSnapshotRepository snapshotRepository,
                            BureauReportingService bureauReportingService,
                            CollectionsEventPublisher eventPublisher,
                            CollectionsProperties properties) {
        this.caseRepository         = caseRepository;
        this.writeOffRepository     = writeOffRepository;
        this.snapshotRepository     = snapshotRepository;
        this.bureauReportingService = bureauReportingService;
        this.eventPublisher         = eventPublisher;
        this.properties             = properties;
    }

    @Override
    public void request(RequestWriteOffCommand cmd) {
        CollectionCase collectionCase = findOrThrow(cmd.caseId());
        guardWriteOffEligible(collectionCase);

        BigDecimal totalDebt = snapshotRepository.findById(collectionCase.getCreditAccountId())
                .map(s -> s.getTotalDebt())
                .orElse(collectionCase.getTotalDebt());

        log.info("WriteOff requested caseId={} creditAccountId={} totalDebt={} reason={}",
                cmd.caseId(), collectionCase.getCreditAccountId(), totalDebt, cmd.reason());
        eventPublisher.publishWriteOffRequested(cmd.caseId(), collectionCase.getCreditAccountId(),
                totalDebt, collectionCase.getDaysDelinquent(), cmd.reason().name(), cmd.requestedBy());
    }

    /** WO-02: the one authorized step — creates the immutable WriteOffRecord and applies it. */
    @Override
    public WriteOffRecord approve(ApproveWriteOffCommand cmd) {
        CollectionCase collectionCase = findOrThrow(cmd.caseId());
        guardWriteOffEligible(collectionCase);

        var snapshot = snapshotRepository.findById(collectionCase.getCreditAccountId());
        BigDecimal principal = snapshot.map(s -> s.getPrincipalBalance()).orElse(collectionCase.getTotalDebt());
        BigDecimal interest  = snapshot.map(s -> s.getAccruedInterestBalance()).orElse(BigDecimal.ZERO);
        BigDecimal penalty   = snapshot.map(s -> s.getPenaltyBalance()).orElse(BigDecimal.ZERO);

        WriteOffRecord record = WriteOffRecord.create(cmd.caseId(), collectionCase.getCreditAccountId(),
                collectionCase.getObligorPartyId(), principal, interest, penalty,
                cmd.authorizedBy(), cmd.authorizationRef(), cmd.reason());
        writeOffRepository.save(record);

        collectionCase.markWrittenOff();
        caseRepository.save(collectionCase);

        log.info("WriteOff executed writeOffId={} caseId={} total={} authorizedBy={}",
                record.getWriteOffId(), cmd.caseId(), record.getTotalWrittenOff(), cmd.authorizedBy());
        // WO-03: credit-portfolio consumes this to zero out balances (existing onWriteOffExecuted consumer)
        eventPublisher.publishWriteOffExecuted(record);

        // BR-03: every write-off is reported to the bureau
        bureauReportingService.createPendingReport(record.getCreditAccountId(), record.getObligorPartyId(),
                BureauEventType.WRITE_OFF, record.getWriteOffId(), record.getTotalWrittenOff());

        return record;
    }

    private void guardWriteOffEligible(CollectionCase collectionCase) {
        if (writeOffRepository.existsByCreditAccountId(collectionCase.getCreditAccountId())) {
            // WO-03
            throw new InvalidCaseStateException("Account " + collectionCase.getCreditAccountId() + " was already written off");
        }
        if (collectionCase.getDaysDelinquent() < properties.getWriteOffThresholdDays()) {
            throw new InvalidCaseStateException("Account " + collectionCase.getCreditAccountId()
                    + " has not reached write_off_threshold_days=" + properties.getWriteOffThresholdDays()
                    + " (currently " + collectionCase.getDaysDelinquent() + ")");
        }
    }

    private CollectionCase findOrThrow(java.util.UUID caseId) {
        return caseRepository.findById(caseId)
                .orElseThrow(() -> new CollectionCaseNotFoundException(caseId.toString()));
    }
}

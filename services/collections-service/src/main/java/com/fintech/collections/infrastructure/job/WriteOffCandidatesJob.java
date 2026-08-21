package com.fintech.collections.infrastructure.job;

import com.fintech.collections.application.CollectionsProperties;
import com.fintech.collections.application.RequestWriteOffCommand;
import com.fintech.collections.application.port.out.CollectionCaseRepository;
import com.fintech.collections.application.service.WriteOffService;
import com.fintech.collections.domain.CaseStatus;
import com.fintech.collections.domain.CollectionCase;
import com.fintech.collections.domain.WriteOffReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * ES-03: weekly — identifies MANAGED/LEGAL cases at or past write_off_threshold_days and
 * publishes WriteOffRequested (WO-01, intent only) for each so they queue up for approval.
 */
@Component
public class WriteOffCandidatesJob {

    private static final Logger log = LoggerFactory.getLogger(WriteOffCandidatesJob.class);

    private final CollectionCaseRepository caseRepository;
    private final WriteOffService writeOffService;
    private final CollectionsProperties properties;

    public WriteOffCandidatesJob(CollectionCaseRepository caseRepository,
                                  WriteOffService writeOffService,
                                  CollectionsProperties properties) {
        this.caseRepository  = caseRepository;
        this.writeOffService = writeOffService;
        this.properties      = properties;
    }

    @Scheduled(cron = "0 0 6 * * MON")
    public void run() {
        List<CollectionCase> candidates = caseRepository.findByStatusInAndDaysDelinquentGreaterThanEqual(
                List.of(CaseStatus.MANAGED, CaseStatus.LEGAL), properties.getWriteOffThresholdDays());
        log.info("WriteOffCandidatesJob starting candidates={}", candidates.size());

        int requested = 0;
        int errors    = 0;
        for (CollectionCase c : candidates) {
            try {
                writeOffService.request(new RequestWriteOffCommand(
                        c.getCaseId(), WriteOffReason.UNRECOVERABLE, "SYSTEM_WRITE_OFF_CANDIDATES_JOB"));
                requested++;
            } catch (Exception ex) {
                errors++;
                log.error("WriteOffCandidatesJob failed caseId={}: {}", c.getCaseId(), ex.getMessage(), ex);
            }
        }
        log.info("WriteOffCandidatesJob finished requested={} errors={}", requested, errors);
    }
}

package com.fintech.collections.infrastructure.adapter.in.api;

import com.fintech.collections.infrastructure.job.AgreementExpirationJob;
import com.fintech.collections.infrastructure.job.BureauReportingJob;
import com.fintech.collections.infrastructure.job.DunningScheduleJob;
import com.fintech.collections.infrastructure.job.PromiseBrokenCheckJob;
import com.fintech.collections.infrastructure.job.WriteOffCandidatesJob;
import com.fintech.shared.time.BusinessClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Dev-only: runs the collections jobs on demand, without the scheduler lock, so a simulated day can
 * run them one after another (the lock's {@code lockAtLeastFor} would silently skip the second day).
 * Exists only with {@code fintech.test-support.enabled=true}; the gateway never routes {@code /internal/*}.
 */
@RestController
@RequestMapping("/internal/test-support")
@ConditionalOnProperty(name = "fintech.test-support.enabled", havingValue = "true")
public class TestSupportController {

    private static final Logger log = LoggerFactory.getLogger(TestSupportController.class);

    private final PromiseBrokenCheckJob promiseBroken;
    private final AgreementExpirationJob agreementExpiration;
    private final DunningScheduleJob dunning;
    private final BureauReportingJob bureauReporting;
    private final WriteOffCandidatesJob writeOffCandidates;
    private final BusinessClock clock;

    public TestSupportController(PromiseBrokenCheckJob promiseBroken, AgreementExpirationJob agreementExpiration,
                                 DunningScheduleJob dunning, BureauReportingJob bureauReporting,
                                 WriteOffCandidatesJob writeOffCandidates, BusinessClock clock) {
        this.promiseBroken       = promiseBroken;
        this.agreementExpiration = agreementExpiration;
        this.dunning             = dunning;
        this.bureauReporting     = bureauReporting;
        this.writeOffCandidates  = writeOffCandidates;
        this.clock               = clock;
    }

    /** The jobs of one business day, in the order the crons run them: 02:00 bureau, 08:00 promises, 08:15 agreements, 10:00 dunning. */
    @PostMapping("/run-daily-jobs")
    ResponseEntity<Map<String, Object>> runDailyJobs() {
        log.info("[test-support] Jobs diarios de cobranza del {}", clock.today());
        bureauReporting.execute();
        promiseBroken.execute();
        agreementExpiration.execute();
        dunning.execute();
        return ResponseEntity.ok(Map.of("businessDate", clock.today().toString(), "jobs", 4));
    }

    /** The Monday job: cases past the write-off threshold get a write-off request. */
    @PostMapping("/run-write-off-candidates")
    ResponseEntity<Map<String, Object>> runWriteOffCandidates() {
        log.info("[test-support] Candidatos a quebranto al {}", clock.today());
        writeOffCandidates.execute();
        return ResponseEntity.ok(Map.of("businessDate", clock.today().toString()));
    }
}

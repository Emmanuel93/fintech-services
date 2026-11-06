package com.fintech.risk.application.service;

import com.fintech.risk.application.port.out.RiskProfileRepository;
import com.fintech.risk.domain.MissingProvisionPolicyException;
import com.fintech.risk.domain.RiskProfile;
import com.fintech.risk.domain.RiskProfileStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import com.fintech.shared.time.BusinessClock;

/**
 * PR-01: nightly full-recompute of every ACTIVE RiskProfile — bucket, ifrs9Stage and provision —
 * publishing {@code RiskAssessmentUpdated} per account (always, even without a stage change). Not
 * incremental, to avoid drift; not transactional at the top so a single failure isolates to its
 * own account (see {@link RiskProfileAssessor}).
 */
@Service
public class RiskAssessmentService {

    private static final Logger log = LoggerFactory.getLogger(RiskAssessmentService.class);

    private final RiskProfileRepository profileRepository;
    private final RiskProfileAssessor assessor;

    public RiskAssessmentService(RiskProfileRepository profileRepository, RiskProfileAssessor assessor) {
        this.profileRepository = profileRepository;
        this.assessor = assessor;
    }

    public Result reassessAll() {
        // The night evaluates the day that just closed: its delinquency and balances are the snapshot of D. Dating
        // the ECL with the instant the job runs (D+1, 01:00) moved the last day of each month into the next
        // period (2 Nov E2E: 7 provision vouchers of 31-Oct in 202611).
        BusinessClock clock = BusinessClock.current();
        Instant now = clock.today().minusDays(1).atTime(23, 59, 59).atZone(clock.zone()).toInstant();
        List<RiskProfile> active = profileRepository.findByStatus(RiskProfileStatus.ACTIVE);
        int assessed = 0, skippedNoPolicy = 0, failed = 0;

        for (RiskProfile profile : active) {
            try {
                assessor.assess(profile.getCreditAccountId(), now);
                assessed++;
            } catch (MissingProvisionPolicyException ex) {
                // PR-03: no ACTIVE policy — record the gap explicitly, never invent a rate
                log.warn("Skipping provision for creditAccountId={} — {}", profile.getCreditAccountId(), ex.getMessage());
                skippedNoPolicy++;
            } catch (Exception ex) {
                log.error("RiskProfile assessment failed creditAccountId={}: {}",
                        profile.getCreditAccountId(), ex.getMessage(), ex);
                failed++;
            }
        }

        log.info("RiskAssessmentJob complete: assessed={} skippedNoPolicy={} failed={} (total ACTIVE={})",
                assessed, skippedNoPolicy, failed, active.size());
        return new Result(active.size(), assessed, skippedNoPolicy, failed);
    }

    public record Result(int totalActive, int assessed, int skippedNoPolicy, int failed) {}
}

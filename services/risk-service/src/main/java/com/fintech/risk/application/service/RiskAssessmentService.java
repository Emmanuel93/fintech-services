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
        Instant now = Instant.now();
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

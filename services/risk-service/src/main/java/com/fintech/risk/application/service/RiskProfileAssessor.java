package com.fintech.risk.application.service;

import com.fintech.risk.application.RiskProperties;
import com.fintech.risk.application.port.out.ProvisionPolicyRepository;
import com.fintech.risk.application.port.out.RiskEventPublisher;
import com.fintech.risk.application.port.out.RiskProfileRepository;
import com.fintech.risk.domain.DelinquencyBucket;
import com.fintech.risk.domain.MissingProvisionPolicyException;
import com.fintech.risk.domain.ProvisionPolicy;
import com.fintech.risk.domain.RiskProfile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Reassesses a single RiskProfile in its own {@code REQUIRES_NEW} transaction so one bad account
 * never rolls back the whole nightly batch — same isolation pattern as credit-portfolio's
 * DelinquencyAccountProcessor.
 */
@Component
public class RiskProfileAssessor {

    private final RiskProfileRepository profileRepository;
    private final ProvisionPolicyRepository policyRepository;
    private final RiskEventPublisher eventPublisher;
    private final RiskProperties properties;

    public RiskProfileAssessor(RiskProfileRepository profileRepository,
                                ProvisionPolicyRepository policyRepository,
                                RiskEventPublisher eventPublisher,
                                RiskProperties properties) {
        this.profileRepository = profileRepository;
        this.policyRepository  = policyRepository;
        this.eventPublisher    = eventPublisher;
        this.properties        = properties;
    }

    /**
     * @throws MissingProvisionPolicyException if there is no ACTIVE policy for the productType (PR-03)
     *         — the caller logs the gap and skips; the profile is left untouched, never provisioned
     *         with an invented rate.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void assess(UUID creditAccountId, Instant now) {
        RiskProfile profile = profileRepository.findByCreditAccountId(creditAccountId).orElse(null);
        if (profile == null || profile.getStatus().isClosed()) return;

        ProvisionPolicy policy = policyRepository.findActiveByProductType(profile.getProductType())
                .orElseThrow(() -> new MissingProvisionPolicyException(profile.getProductType()));

        DelinquencyBucket bucket = DelinquencyBucket.fromDaysDelinquent(profile.getDaysDelinquent());
        BigDecimal rate = policy.rateFor(bucket);

        boolean stageChanged = profile.reassess(
                properties.getStage2DaysThreshold(),
                properties.getStage3DaysThreshold(),
                properties.getCureMonths(),
                rate, now);

        profileRepository.save(profile);
        eventPublisher.publishRiskAssessmentUpdated(profile, stageChanged);  // siempre (PR-01)
    }
}

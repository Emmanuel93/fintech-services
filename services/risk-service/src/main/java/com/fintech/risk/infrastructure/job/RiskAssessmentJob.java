package com.fintech.risk.infrastructure.job;

import com.fintech.risk.application.service.RiskAssessmentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * PR-01: recomputes bucket/stage/provision for every ACTIVE RiskProfile and publishes
 * risk.assessment-updated per account. Runs at 01:00 — after credit-portfolio's
 * DelinquencyCalculationJob (23:59) so daysDelinquent is fresh, and before the accounting close.
 */
@Component
public class RiskAssessmentJob {

    private static final Logger log = LoggerFactory.getLogger(RiskAssessmentJob.class);

    private final RiskAssessmentService riskAssessmentService;

    public RiskAssessmentJob(RiskAssessmentService riskAssessmentService) {
        this.riskAssessmentService = riskAssessmentService;
    }

    @Scheduled(cron = "0 0 1 * * *")
    public void run() {
        log.info("RiskAssessmentJob starting nightly recompute");
        RiskAssessmentService.Result result = riskAssessmentService.reassessAll();
        log.info("RiskAssessmentJob finished: {}", result);
    }
}

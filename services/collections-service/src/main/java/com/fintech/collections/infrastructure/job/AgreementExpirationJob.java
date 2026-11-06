package com.fintech.collections.infrastructure.job;

import com.fintech.collections.application.service.AgreementService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

/** AG-05: 08:15 daily — PROPOSED agreements with no debtor response within the window -> EXPIRED. */
@Component
public class AgreementExpirationJob {

    private static final Logger log = LoggerFactory.getLogger(AgreementExpirationJob.class);

    private final AgreementService agreementService;

    public AgreementExpirationJob(AgreementService agreementService) {
        this.agreementService = agreementService;
    }

    @SchedulerLock(name = "collections.agreement-expiration", lockAtMostFor = "PT1H", lockAtLeastFor = "PT30S")
    @Scheduled(cron = "0 15 8 * * *", zone = "${fintech.business.zone:America/Mexico_City}")
    public void run() {
        execute();
    }

    /** The job's work without the scheduler lock: what test support runs once per simulated day. */
    public void execute() {
        log.info("AgreementExpirationJob starting");
        agreementService.expireStaleProposals();
        log.info("AgreementExpirationJob finished");
    }
}

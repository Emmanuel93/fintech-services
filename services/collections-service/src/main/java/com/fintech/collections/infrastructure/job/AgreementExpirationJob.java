package com.fintech.collections.infrastructure.job;

import com.fintech.collections.application.service.AgreementService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** AG-05: 08:15 daily — PROPOSED agreements with no debtor response within the window -> EXPIRED. */
@Component
public class AgreementExpirationJob {

    private static final Logger log = LoggerFactory.getLogger(AgreementExpirationJob.class);

    private final AgreementService agreementService;

    public AgreementExpirationJob(AgreementService agreementService) {
        this.agreementService = agreementService;
    }

    @Scheduled(cron = "0 15 8 * * *")
    public void run() {
        log.info("AgreementExpirationJob starting");
        agreementService.expireStaleProposals();
        log.info("AgreementExpirationJob finished");
    }
}

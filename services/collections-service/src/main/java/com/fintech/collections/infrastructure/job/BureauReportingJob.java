package com.fintech.collections.infrastructure.job;

import com.fintech.collections.application.service.BureauReportingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** BR-04/BR-05: 02:00 daily — submits PENDING/FAILED bureau reports, indefinite retry on failure. */
@Component
public class BureauReportingJob {

    private static final Logger log = LoggerFactory.getLogger(BureauReportingJob.class);

    private final BureauReportingService bureauReportingService;

    public BureauReportingJob(BureauReportingService bureauReportingService) {
        this.bureauReportingService = bureauReportingService;
    }

    @Scheduled(cron = "0 0 2 * * *")
    public void run() {
        log.info("BureauReportingJob starting");
        bureauReportingService.submitPending();
        log.info("BureauReportingJob finished");
    }
}

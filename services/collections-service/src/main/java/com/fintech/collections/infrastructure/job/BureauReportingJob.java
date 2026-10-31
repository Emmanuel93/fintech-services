package com.fintech.collections.infrastructure.job;

import com.fintech.collections.application.service.BureauReportingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

/** BR-04/BR-05: 02:00 daily — submits PENDING/FAILED bureau reports, indefinite retry on failure. */
@Component
public class BureauReportingJob {

    private static final Logger log = LoggerFactory.getLogger(BureauReportingJob.class);

    private final BureauReportingService bureauReportingService;

    public BureauReportingJob(BureauReportingService bureauReportingService) {
        this.bureauReportingService = bureauReportingService;
    }

    @SchedulerLock(name = "collections.bureau-reporting", lockAtMostFor = "PT1H", lockAtLeastFor = "PT30S")
    @Scheduled(cron = "0 0 2 * * *", zone = "${fintech.business.zone:America/Mexico_City}")
    public void run() {
        log.info("BureauReportingJob starting");
        bureauReportingService.submitPending();
        log.info("BureauReportingJob finished");
    }
}

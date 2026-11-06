package com.fintech.collections.infrastructure.job;

import com.fintech.collections.application.service.PromiseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import com.fintech.shared.time.BusinessClock;

/** PP-04: 08:00 daily — active promises whose promisedDate has already passed are BROKEN. */
@Component
public class PromiseBrokenCheckJob {


    private static final Logger log = LoggerFactory.getLogger(PromiseBrokenCheckJob.class);

    private final PromiseService promiseService;
    private final BusinessClock businessClock;

    public PromiseBrokenCheckJob(PromiseService promiseService, BusinessClock businessClock) {
        this.businessClock = businessClock;
        this.promiseService = promiseService;
    }

    @SchedulerLock(name = "collections.promise-broken-check", lockAtMostFor = "PT1H", lockAtLeastFor = "PT30S")
    @Scheduled(cron = "0 0 8 * * *", zone = "${fintech.business.zone:America/Mexico_City}")
    public void run() {
        execute();
    }

    /** The job's work without the scheduler lock: what test support runs once per simulated day. */
    public void execute() {
        LocalDate today = businessClock.today();
        log.info("PromiseBrokenCheckJob starting date={}", today);
        promiseService.checkBrokenPromises(today);
        log.info("PromiseBrokenCheckJob finished date={}", today);
    }
}

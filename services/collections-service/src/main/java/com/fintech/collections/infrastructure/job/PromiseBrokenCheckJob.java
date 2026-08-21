package com.fintech.collections.infrastructure.job;

import com.fintech.collections.application.service.PromiseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/** PP-04: 08:00 daily — active promises whose promisedDate has already passed are BROKEN. */
@Component
public class PromiseBrokenCheckJob {

    private static final Logger log = LoggerFactory.getLogger(PromiseBrokenCheckJob.class);

    private final PromiseService promiseService;

    public PromiseBrokenCheckJob(PromiseService promiseService) {
        this.promiseService = promiseService;
    }

    @Scheduled(cron = "0 0 8 * * *")
    public void run() {
        LocalDate today = LocalDate.now();
        log.info("PromiseBrokenCheckJob starting date={}", today);
        promiseService.checkBrokenPromises(today);
        log.info("PromiseBrokenCheckJob finished date={}", today);
    }
}

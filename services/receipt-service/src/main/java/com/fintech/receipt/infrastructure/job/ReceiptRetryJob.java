package com.fintech.receipt.infrastructure.job;

import com.fintech.receipt.application.service.ReceiptRetryService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Retries the failed or stuck receipts every few minutes, on one replica (ShedLock). */
@Component
@ConditionalOnProperty(name = "fintech.receipt.retry.enabled", havingValue = "true", matchIfMissing = true)
public class ReceiptRetryJob {

    private final ReceiptRetryService retry;

    public ReceiptRetryJob(ReceiptRetryService retry) {
        this.retry = retry;
    }

    @SchedulerLock(name = "receipt.retry", lockAtMostFor = "PT15M")
    @Scheduled(fixedDelayString = "${fintech.receipt.retry.interval:PT2M}", initialDelayString = "PT1M")
    public void run() {
        retry.retry();
    }
}

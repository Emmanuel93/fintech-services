package com.fintech.statement.infrastructure.job;

import com.fintech.statement.application.service.StatementRetryService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Reintenta los estados de cuenta fallidos y los que un pod dejó a medias. Una réplica: lo normal es
 * que no haya nada que hacer y la vuelta sea una consulta.
 */
@Component
@ConditionalOnProperty(name = "fintech.statement.retry.enabled", havingValue = "true", matchIfMissing = true)
public class StatementRetryJob {

    private final StatementRetryService retry;

    public StatementRetryJob(StatementRetryService retry) {
        this.retry = retry;
    }

    @SchedulerLock(name = "statement.retry", lockAtMostFor = "PT15M")
    @Scheduled(fixedDelayString = "${fintech.statement.retry.interval:PT2M}", initialDelayString = "PT1M")
    public void run() {
        retry.retry();
    }
}

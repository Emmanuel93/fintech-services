package com.fintech.closing.infrastructure.job;

import com.fintech.closing.application.service.cutoff.CutoffRunMonitor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * La vuelta del monitor de cortes. Es de intervalo y no de reloj: revisa corridas abiertas, y si no
 * hay ninguna no hace más que una consulta.
 */
@Component
@ConditionalOnProperty(name = "fintech.closing.cutoff.job-enabled", havingValue = "true", matchIfMissing = true)
public class CutoffRunMonitorJob {

    private final CutoffRunMonitor monitor;

    public CutoffRunMonitorJob(CutoffRunMonitor monitor) {
        this.monitor = monitor;
    }

    @SchedulerLock(name = "closing.cutoff-monitor", lockAtMostFor = "PT10M")
    @Scheduled(fixedDelayString = "${fintech.closing.cutoff.monitor-interval:PT15S}", initialDelayString = "PT20S")
    public void tick() {
        monitor.tick();
    }
}

package com.fintech.charges.infrastructure.job;

import com.fintech.charges.application.service.dayclose.AccrualDayMonitor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * El monitor del cierre por cuenta, cada 15 segundos en <b>una</b> réplica. Las cuentas las devengan
 * todas; éste sólo recupera, reintenta, suelta, reenvía y cierra. Sin días abiertos son dos consultas.
 */
@Component
class AccrualMonitorJob {

    private final AccrualDayMonitor monitor;

    AccrualMonitorJob(AccrualDayMonitor monitor) {
        this.monitor = monitor;
    }

    @SchedulerLock(name = "charges.accrual-monitor", lockAtMostFor = "PT10M", lockAtLeastFor = "PT5S")
    @Scheduled(fixedDelayString = "${fintech.day-close.units.monitor-interval:PT15S}",
               initialDelayString = "${fintech.day-close.units.monitor-interval:PT15S}")
    public void run() {
        monitor.tick();
    }
}

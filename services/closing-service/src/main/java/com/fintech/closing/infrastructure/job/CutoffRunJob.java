package com.fintech.closing.infrastructure.job;

import com.fintech.closing.application.ClosingProperties;
import com.fintech.closing.application.port.out.BalanceMovementRepository;
import com.fintech.closing.application.port.out.CutoffUnitStore;
import com.fintech.closing.application.port.out.PortfolioDayCloseLog;
import com.fintech.closing.application.service.cutoff.CutoffRunService;
import com.fintech.shared.time.BusinessClock;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * La <b>reanudación</b> del corte. El disparo normal es el aviso de cartera
 * ({@code credit-portfolio.day-closed}, ver {@code DayClosedListener}): el cierre del día D empieza a
 * las 00:05 de D+1 en cargos, sigue en cartera y termina aquí.
 *
 * <p>Cada 15 minutos:
 * <ol>
 *   <li>corta, en orden y por las mismas compuertas, los días que cartera cerró y closing no;</li>
 *   <li>vuelve a mandar los avisos de fin de corte que no salieron (cargos los espera para seguir);</li>
 *   <li>dice en el log qué días tienen cortes vivos sin cierre de cartera. <b>No los corta</b>: sin
 *       ese cierre el interés o la mora del día pueden faltar;</li>
 *   <li>borra los movimientos con fecha valor que ningún corte va a mirar.</li>
 * </ol>
 */
@Component
@ConditionalOnProperty(name = "fintech.closing.cutoff.job-enabled", havingValue = "true", matchIfMissing = true)
public class CutoffRunJob {

    private static final Logger log = LoggerFactory.getLogger(CutoffRunJob.class);

    private final CutoffRunService cutoffs;
    private final CutoffUnitStore store;
    private final PortfolioDayCloseLog dayCloses;
    private final BalanceMovementRepository movements;
    private final ClosingProperties properties;
    private final BusinessClock clock;

    public CutoffRunJob(CutoffRunService cutoffs, CutoffUnitStore store, PortfolioDayCloseLog dayCloses,
                        BalanceMovementRepository movements, ClosingProperties properties, BusinessClock clock) {
        this.cutoffs    = cutoffs;
        this.store      = store;
        this.dayCloses  = dayCloses;
        this.movements  = movements;
        this.properties = properties;
        this.clock      = clock;
    }

    @SchedulerLock(name = "closing.cutoff-run", lockAtMostFor = "PT1H", lockAtLeastFor = "PT30S")
    @Scheduled(cron = "${fintech.closing.cutoff.cron:0 15/15 * * * *}", zone = "${fintech.business.zone:America/Mexico_City}")
    public void run() {
        cutoffs.advance("reanudación").forEach(c -> log.info("Corte del {}: {} cuentas, estado {}",
                c.businessDate(), c.plannedUnits(), c.status()));
        int avisos = cutoffs.announcePendingCompletions();
        if (avisos > 0) log.warn("Reenviados {} avisos de fin de corte que no habían salido", avisos);

        LocalDate hoy = clock.today();
        for (LocalDate dia : store.pendingCutoffDates(hoy.minusDays(properties.getCutoff().getLookbackDays()), hoy)) {
            if (!dayCloses.closed(dia)) {
                log.warn("El {} tiene cortes vivos y cartera no lo ha cerrado: no se corta hasta que lo cierre", dia);
            }
        }
        int borrados = movements.pruneBefore(hoy.minusDays(properties.getCutoff().getMovementRetentionDays()));
        if (borrados > 0) log.info("Movimientos con fecha valor depurados: {}", borrados);
    }
}

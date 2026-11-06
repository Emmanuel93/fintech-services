package com.fintech.creditportfolio.infrastructure.job;

import com.fintech.creditportfolio.application.port.out.DayCloseRepository;
import com.fintech.shared.time.BusinessClock;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * La reanudación del cierre: cada 15 minutos vuelve a intentar los días que cargos ya avisó y cartera no
 * ha cerrado —esperaban una compuerta o fallaron—, en orden y por las mismas compuertas. Lo normal es
 * que no encuentre nada.
 *
 * <p>No cierra un día que cargos no avisó: eso sería cerrar sin interés. Si a las 02:00 cargos sigue
 * sin avisar ayer, lo dice en el log, que es lo que hay que mirar.
 */
@Component
public class DayCloseRetryJob {

    private static final Logger log = LoggerFactory.getLogger(DayCloseRetryJob.class);
    private static final LocalTime ALERTA = LocalTime.of(2, 0);

    private final DayCloseRepository dayCloses;
    private final DayCloseRunner runner;
    private final AccountDayReleaser releaser;
    private final BusinessClock businessClock;

    public DayCloseRetryJob(DayCloseRepository dayCloses, DayCloseRunner runner, AccountDayReleaser releaser,
                            BusinessClock businessClock) {
        this.dayCloses     = dayCloses;
        this.runner        = runner;
        this.releaser      = releaser;
        this.businessClock = businessClock;
    }

    @SchedulerLock(name = "credit-portfolio.day-close-retry", lockAtMostFor = "PT3H", lockAtLeastFor = "PT30S")
    @Scheduled(cron = "${fintech.day-close.retry-cron:0 10/15 * * * *}", zone = "${fintech.business.zone:America/Mexico_City}")
    public void run() {
        runner.advance("reanudación");
        releaser.releasePending();
        LocalDate ayer = businessClock.today().minusDays(1);
        boolean tarde = !LocalTime.now(businessClock.clock()).isBefore(ALERTA);
        if (tarde && dayCloses.chargesDay(ayer).isEmpty()) {
            log.warn("Cargos no ha avisado el cierre del {}: cartera no lo cierra sin su interés", ayer);
        }
    }
}

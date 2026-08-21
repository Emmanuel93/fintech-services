package com.fintech.charges.infrastructure.job;

import com.fintech.charges.application.service.InterestAccrualService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Component
public class DailyAccrualJob {

    private static final Logger log = LoggerFactory.getLogger(DailyAccrualJob.class);

    private final InterestAccrualService accrualService;

    public DailyAccrualJob(InterestAccrualService accrualService) {
        this.accrualService = accrualService;
    }

    // Runs at 23:00 every day — per-schedule tx isolation via service proxy
    @Scheduled(cron = "0 0 23 * * *")
    public void runDailyAccrual() {
        runDailyAccrualFor(LocalDate.now());
    }

    /**
     * Devenga por una fecha explícita.
     *
     * <p>El devengo es idempotente por día ({@code needsAccrual}/{@code markAccruedFor}), así que
     * repetir la corrida de hoy no acumula nada: para tener un mes de intereses hay que <b>correr el
     * reloj</b>, un día a la vez. Con la fecha fija dentro del job eso era imposible desde fuera y la
     * única forma de sembrar historia era escribir saldos a mano.
     */
    public void runDailyAccrualFor(LocalDate today) {
        List<UUID> scheduleIds = accrualService.findScheduleIdsForAccrual(today);
        log.info("DailyAccrualJob starting date={} schedules={}", today, scheduleIds.size());

        int success = 0;
        int errors  = 0;
        for (UUID scheduleId : scheduleIds) {
            try {
                accrualService.accrueInterestForSchedule(scheduleId, today);
                success++;
            } catch (Exception ex) {
                errors++;
                log.error("DailyAccrualJob failed scheduleId={}: {}", scheduleId, ex.getMessage(), ex);
            }
        }
        log.info("DailyAccrualJob finished date={} success={} errors={}", today, success, errors);
    }
}

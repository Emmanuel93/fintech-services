package com.fintech.charges.infrastructure.job;

import com.fintech.charges.application.service.InterestAccrualService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * El devengo ordinario de un día. No tiene cron propio: lo dispara {@link DayCloseAccrualJob} a las
 * 00:05 del día siguiente, antes del moratorio y de la mora.
 */
@Component
public class DailyAccrualJob {


    private static final Logger log = LoggerFactory.getLogger(DailyAccrualJob.class);

    private final InterestAccrualService accrualService;

    public DailyAccrualJob(InterestAccrualService accrualService) {
        this.accrualService = accrualService;
    }

    /**
     * Devenga por una fecha explícita.
     *
     * <p>El devengo es idempotente por día ({@code needsAccrual}/{@code markAccruedFor}), así que
     * repetir la corrida de hoy no acumula nada: para tener un mes de intereses hay que <b>correr el
     * reloj</b>, un día a la vez. Con la fecha fija dentro del job eso era imposible desde fuera y la
     * única forma de sembrar historia era escribir saldos a mano.
     */
    public AccrualTally runDailyAccrualFor(LocalDate today) {
        return runDailyAccrualFor(today, false);
    }

    /** @param dayClose si corre dentro del cierre del día: sus cargos forman el lote que cartera cuadra. */
    public AccrualTally runDailyAccrualFor(LocalDate today, boolean dayClose) {
        List<UUID> scheduleIds = accrualService.findScheduleIdsForAccrual(today);
        log.info("DailyAccrualJob starting date={} schedules={}", today, scheduleIds.size());

        int success = 0;
        int errors  = 0;
        for (UUID scheduleId : scheduleIds) {
            try {
                accrualService.accrueInterestForSchedule(scheduleId, today, dayClose);
                success++;
            } catch (Exception ex) {
                errors++;
                log.error("DailyAccrualJob failed scheduleId={}: {}", scheduleId, ex.getMessage(), ex);
            }
        }
        log.info("DailyAccrualJob finished date={} success={} errors={}", today, success, errors);
        return new AccrualTally(scheduleIds.size(), success, errors);
    }
}

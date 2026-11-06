package com.fintech.charges.infrastructure.job;

import com.fintech.charges.application.service.InterestAccrualService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * El devengo moratorio de un día. Sin cron propio: {@link DayCloseAccrualJob} lo corre después del
 * ordinario y antes de que cartera recalcule la mora.
 */
@Component
public class MoratoriumAccrualJob {


    private static final Logger log = LoggerFactory.getLogger(MoratoriumAccrualJob.class);

    private final InterestAccrualService accrualService;

    public MoratoriumAccrualJob(InterestAccrualService accrualService) {
        this.accrualService = accrualService;
    }

    /** Igual que el ordinario: la fecha se recibe para poder devengar historia día a día. */
    public AccrualTally runMoratoriumAccrualFor(LocalDate today) {
        return runMoratoriumAccrualFor(today, false);
    }

    /** @param dayClose si corre dentro del cierre del día: sus cargos forman el lote que cartera cuadra. */
    public AccrualTally runMoratoriumAccrualFor(LocalDate today, boolean dayClose) {
        List<UUID> scheduleIds = accrualService.findMoratoriumScheduleIds(today);
        log.info("MoratoriumAccrualJob starting date={} schedules={}", today, scheduleIds.size());

        int success = 0;
        int errors  = 0;
        for (UUID scheduleId : scheduleIds) {
            try {
                accrualService.accrueMoratoriumForSchedule(scheduleId, today, dayClose);
                success++;
            } catch (Exception ex) {
                errors++;
                log.error("MoratoriumAccrualJob failed scheduleId={}: {}", scheduleId, ex.getMessage(), ex);
            }
        }
        log.info("MoratoriumAccrualJob finished date={} success={} errors={}", today, success, errors);
        return new AccrualTally(scheduleIds.size(), success, errors);
    }
}

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
public class MoratoriumAccrualJob {

    private static final Logger log = LoggerFactory.getLogger(MoratoriumAccrualJob.class);

    private final InterestAccrualService accrualService;

    public MoratoriumAccrualJob(InterestAccrualService accrualService) {
        this.accrualService = accrualService;
    }

    // Runs at 23:30 every day (after ordinary interest at 23:00)
    @Scheduled(cron = "0 30 23 * * *")
    public void runMoratoriumAccrual() {
        runMoratoriumAccrualFor(LocalDate.now());
    }

    /** Igual que el ordinario: la fecha se recibe para poder devengar historia día a día. */
    public void runMoratoriumAccrualFor(LocalDate today) {
        List<UUID> scheduleIds = accrualService.findMoratoriumScheduleIds(today);
        log.info("MoratoriumAccrualJob starting date={} schedules={}", today, scheduleIds.size());

        int success = 0;
        int errors  = 0;
        for (UUID scheduleId : scheduleIds) {
            try {
                accrualService.accrueMoratoriumForSchedule(scheduleId, today);
                success++;
            } catch (Exception ex) {
                errors++;
                log.error("MoratoriumAccrualJob failed scheduleId={}: {}", scheduleId, ex.getMessage(), ex);
            }
        }
        log.info("MoratoriumAccrualJob finished date={} success={} errors={}", today, success, errors);
    }
}

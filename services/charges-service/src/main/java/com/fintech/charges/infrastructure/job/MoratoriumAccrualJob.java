package com.fintech.charges.infrastructure.job;

import com.fintech.charges.application.service.InterestAccrualService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import com.fintech.shared.time.BusinessClock;

@Component
public class MoratoriumAccrualJob {


    private static final Logger log = LoggerFactory.getLogger(MoratoriumAccrualJob.class);

    private final InterestAccrualService accrualService;
    private final BusinessClock businessClock;

    public MoratoriumAccrualJob(InterestAccrualService accrualService, BusinessClock businessClock) {
        this.businessClock = businessClock;
        this.accrualService = accrualService;
    }

    // Runs at 23:30 every day (after ordinary interest at 23:00)
    @SchedulerLock(name = "charges.moratorium-accrual", lockAtMostFor = "PT2H", lockAtLeastFor = "PT30S")
    @Scheduled(cron = "0 30 23 * * *", zone = "${fintech.business.zone:America/Mexico_City}")
    public void runMoratoriumAccrual() {
        runMoratoriumAccrualFor(businessClock.today());
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

package com.fintech.commission.infrastructure.job;

import com.fintech.commission.application.service.LiquidationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.YearMonth;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import com.fintech.shared.time.BusinessClock;

/** LB-03: consolida las comisiones ACCRUED del período anterior en un batch por beneficiario. Corre el día 5 a las 03:30. */
@Component
public class CommissionLiquidationJob {


    private static final Logger log = LoggerFactory.getLogger(CommissionLiquidationJob.class);
    private final LiquidationService liquidationService;
    private final BusinessClock businessClock;

    public CommissionLiquidationJob(LiquidationService liquidationService, BusinessClock businessClock) {
        this.liquidationService = liquidationService;
        this.businessClock = businessClock;
    }

    @SchedulerLock(name = "commission.liquidation", lockAtMostFor = "PT2H", lockAtLeastFor = "PT30S")
    @Scheduled(cron = "0 30 3 5 * *", zone = "${fintech.business.zone:America/Mexico_City}")
    public void run() {
        String previousPeriod = YearMonth.now(businessClock.clock()).minusMonths(1).toString().replace("-", "");
        log.info("CommissionLiquidationJob starting for period={}", previousPeriod);
        int batches = liquidationService.runLiquidation(previousPeriod);
        log.info("CommissionLiquidationJob finished period={} batches={}", previousPeriod, batches);
    }
}

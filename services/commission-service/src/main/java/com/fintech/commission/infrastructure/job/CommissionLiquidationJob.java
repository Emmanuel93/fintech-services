package com.fintech.commission.infrastructure.job;

import com.fintech.commission.application.service.LiquidationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.YearMonth;

/** LB-03: consolida las comisiones ACCRUED del período anterior en un batch por beneficiario. Corre el día 5 a las 03:30. */
@Component
public class CommissionLiquidationJob {

    private static final Logger log = LoggerFactory.getLogger(CommissionLiquidationJob.class);
    private final LiquidationService liquidationService;

    public CommissionLiquidationJob(LiquidationService liquidationService) { this.liquidationService = liquidationService; }

    @Scheduled(cron = "0 30 3 5 * *")
    public void run() {
        String previousPeriod = YearMonth.now().minusMonths(1).toString().replace("-", "");
        log.info("CommissionLiquidationJob starting for period={}", previousPeriod);
        int batches = liquidationService.runLiquidation(previousPeriod);
        log.info("CommissionLiquidationJob finished period={} batches={}", previousPeriod, batches);
    }
}

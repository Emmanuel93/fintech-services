package com.fintech.accounting.infrastructure.job;

import com.fintech.accounting.application.service.BillingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.YearMonth;

/**
 * Consolida la facturación del período anterior: un CFDI por party con sus intereses/comisiones/IVA
 * devengados. Corre el día 1 a las 03:00; emite accounting.invoice-requested a Facturación.
 */
@Component
public class BillingRunJob {

    private static final Logger log = LoggerFactory.getLogger(BillingRunJob.class);
    private final BillingService billingService;

    public BillingRunJob(BillingService billingService) { this.billingService = billingService; }

    @Scheduled(cron = "0 0 3 1 * *")
    public void run() {
        String previousPeriod = YearMonth.now().minusMonths(1).toString().replace("-", "");
        log.info("BillingRunJob starting for period={}", previousPeriod);
        int invoices = billingService.runBilling(previousPeriod);
        log.info("BillingRunJob finished period={} invoices={}", previousPeriod, invoices);
    }
}

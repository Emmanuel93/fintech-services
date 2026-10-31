package com.fintech.creditportfolio.infrastructure.job;

import com.fintech.creditportfolio.application.port.out.CreditAccountRepository;
import com.fintech.creditportfolio.domain.CreditAccount;
import com.fintech.creditportfolio.domain.CreditAccountStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import com.fintech.shared.time.BusinessClock;

/**
 * Nightly at 23:59 — recalculates daysDelinquent for every ACTIVE credit account
 * and publishes DelinquencyStatusUpdated for collections-service to consume.
 *
 * Each account runs in its own REQUIRES_NEW transaction via DelinquencyAccountProcessor,
 * so a single account failure does not abort the entire batch.
 */
@Component
public class DelinquencyCalculationJob {


    private static final Logger log = LoggerFactory.getLogger(DelinquencyCalculationJob.class);

    private final CreditAccountRepository accountRepository;
    private final DelinquencyAccountProcessor processor;
    private final BusinessClock businessClock;

    public DelinquencyCalculationJob(CreditAccountRepository accountRepository,
                                      DelinquencyAccountProcessor processor,
                                     BusinessClock businessClock) {
        this.businessClock = businessClock;
        this.accountRepository = accountRepository;
        this.processor         = processor;
    }

    @SchedulerLock(name = "credit-portfolio.delinquency-calculation", lockAtMostFor = "PT2H", lockAtLeastFor = "PT30S")
    @Scheduled(cron = "0 59 23 * * *", zone = "${fintech.business.zone:America/Mexico_City}")
    public void run() {
        runFor(businessClock.today());
    }

    /**
     * El trabajo del job para una fecha explícita, <b>sin candado</b>: lo usa el soporte de pruebas,
     * que lo dispara varias veces seguidas al correr el reloj. El candado es para el cron, que
     * corre en cada réplica; una llamada explícita ya es una sola.
     */
    public void runFor(LocalDate today) {
        List<CreditAccount> activeAccounts = accountRepository.findAllByStatus(CreditAccountStatus.ACTIVE);
        log.info("DelinquencyCalculationJob starting accounts={} date={}", activeAccounts.size(), today);

        int updated = 0;
        int errors  = 0;
        for (CreditAccount account : activeAccounts) {
            try {
                processor.process(account, today);
                updated++;
            } catch (Exception ex) {
                log.error("DelinquencyCalculationJob failed creditAccountId={}: {}",
                        account.getCreditAccountId(), ex.getMessage(), ex);
                errors++;
            }
        }
        log.info("DelinquencyCalculationJob finished updated={} errors={} date={}", updated, errors, today);
    }
}

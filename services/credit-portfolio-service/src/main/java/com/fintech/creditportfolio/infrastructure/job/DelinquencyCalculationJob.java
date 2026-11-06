package com.fintech.creditportfolio.infrastructure.job;

import com.fintech.creditportfolio.application.port.out.CreditAccountRepository;
import com.fintech.creditportfolio.domain.CreditAccount;
import com.fintech.creditportfolio.domain.CreditAccountStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import com.fintech.shared.time.BusinessClock;

/**
 * Recalcula los días de atraso de cada cuenta ACTIVA y publica DelinquencyStatusUpdated.
 *
 * <p>Sin cron propio: lo corre {@link DayCloseRunner} en el cierre del día, a partir de las 00:05,
 * con la fecha <b>del día que empieza</b>. La mora corre desde las 00:00: la cuota que vencía el 10 y
 * no se pagó amanece el 11 con un día de atraso.
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

    /** Al día de hoy. Para pruebas y para correrlo a mano; el cierre llama {@link #runFor}. */
    public void run() {
        runFor(businessClock.today());
    }

    /**
     * El trabajo del job para una fecha explícita, <b>sin candado</b>: lo usa el soporte de pruebas,
     * que lo dispara varias veces seguidas al correr el reloj. El candado es para el cron, que
     * corre en cada réplica; una llamada explícita ya es una sola.
     */
    public Tally runFor(LocalDate today) {
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
        return new Tally(activeAccounts.size(), errors);
    }

    /** Cuántas cuentas se recalcularon y cuántas fallaron. */
    public record Tally(int accounts, int errors) {}
}

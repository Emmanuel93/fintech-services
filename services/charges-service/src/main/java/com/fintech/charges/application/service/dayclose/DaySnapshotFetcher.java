package com.fintech.charges.application.service.dayclose;

import com.fintech.charges.application.port.out.DaySnapshotStore;
import com.fintech.charges.application.port.out.PortfolioDaySnapshots;
import com.fintech.charges.application.port.out.PortfolioDaySnapshots.AccountSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Brings the portfolio's snapshot of D into charges before the day is planned: asks the portfolio to
 * take it (it takes it once, whoever asks), copies it page by page and checks that nothing was lost on
 * the way. The copy is idempotent, so a retry after a failed pull just completes it.
 */
@Service
public class DaySnapshotFetcher {

    private static final Logger log = LoggerFactory.getLogger(DaySnapshotFetcher.class);

    /** Why the snapshot is not available yet, or how many accounts it has. */
    public record Outcome(boolean ready, String detail, int accounts) {
        public static Outcome waiting(String detail) { return new Outcome(false, detail, 0); }
        public static Outcome ready(int accounts) { return new Outcome(true, accounts + " cuentas en la foto", accounts); }
    }

    private final PortfolioDaySnapshots portfolio;
    private final DaySnapshotStore store;
    private final int pageSize;

    public DaySnapshotFetcher(PortfolioDaySnapshots portfolio, DaySnapshotStore store,
                              @Value("${fintech.day-close.snapshot-page:1000}") int pageSize) {
        this.portfolio = portfolio;
        this.store     = store;
        this.pageSize  = Math.max(pageSize, 1);
    }

    public Outcome fetch(LocalDate businessDate) {
        var status = portfolio.take(businessDate);
        if (!status.taken()) {
            return Outcome.waiting("cartera no ha tomado la foto del " + businessDate + ": " + status.detail());
        }
        long started = System.nanoTime();
        int copied = 0;
        UUID after = null;
        while (true) {
            List<AccountSnapshot> page = portfolio.page(businessDate, after, pageSize);
            if (page.isEmpty()) break;
            copied += store.store(businessDate, page);
            after = page.get(page.size() - 1).creditAccountId();
            if (page.size() < pageSize) break;
        }
        int local = store.count(businessDate);
        if (local != status.accounts()) {
            return Outcome.waiting("la foto del " + businessDate + " llegó incompleta: cartera tiene "
                                   + status.accounts() + " cuentas y aquí hay " + local);
        }
        log.info("Foto del {} copiada de cartera: {} cuentas ({} nuevas) en {} ms", businessDate, local, copied,
                (System.nanoTime() - started) / 1_000_000);
        return Outcome.ready(local);
    }
}

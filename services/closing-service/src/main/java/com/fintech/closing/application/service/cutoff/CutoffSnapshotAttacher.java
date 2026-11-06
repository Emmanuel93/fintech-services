package com.fintech.closing.application.service.cutoff;

import com.fintech.closing.application.port.out.CutoffUnitStore;
import com.fintech.closing.application.port.out.PortfolioDaySnapshots;
import com.fintech.closing.application.port.out.PortfolioDaySnapshots.AccountSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Copies the portfolio's snapshot of D onto the units of a cutoff run: days past due as of D and the
 * principal at D. It runs when the run is planned, in batches, so sealing thousands of accounts does
 * not call the portfolio once per account; a unit that still lacks it when sealed asks for its own.
 */
@Service
public class CutoffSnapshotAttacher {

    private static final Logger log = LoggerFactory.getLogger(CutoffSnapshotAttacher.class);
    private static final int PAGE = 500;

    private final CutoffUnitStore store;
    private final PortfolioDaySnapshots portfolio;

    public CutoffSnapshotAttacher(CutoffUnitStore store, PortfolioDaySnapshots portfolio) {
        this.store     = store;
        this.portfolio = portfolio;
    }

    /** @return how many units received their snapshot in this call */
    public int attach(UUID runId, LocalDate businessDate) {
        int attached = 0;
        int missing = 0;
        while (true) {
            List<UUID> pending = store.accountsWithoutSnapshot(runId, PAGE);
            if (pending.isEmpty()) break;
            List<AccountSnapshot> found = portfolio.lookup(businessDate, pending);
            int n = store.attachSnapshots(runId, found);
            attached += n;
            missing = pending.size() - found.size();
            // Accounts the portfolio has no row for stay without a snapshot; stop or this would loop on them.
            if (found.isEmpty() || pending.size() < PAGE) break;
        }
        if (missing > 0) {
            log.warn("Corte del {}: {} cuentas sin foto de cartera; fallarán al sellar hasta que exista", businessDate, missing);
        }
        return attached;
    }
}

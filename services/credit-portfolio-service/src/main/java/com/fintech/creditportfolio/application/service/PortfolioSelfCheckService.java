package com.fintech.creditportfolio.application.service;

import com.fintech.creditportfolio.application.port.out.PortfolioSelfCheckQueries;
import com.fintech.creditportfolio.application.port.out.PortfolioSelfCheckQueries.Book;
import com.fintech.creditportfolio.application.port.out.PortfolioSelfCheckQueries.LedgerGap;
import com.fintech.creditportfolio.application.port.out.PortfolioSelfCheckQueries.NegativeBalance;
import com.fintech.creditportfolio.application.port.out.PortfolioSelfCheckQueries.OpenClose;
import com.fintech.creditportfolio.application.port.out.PortfolioSelfCheckQueries.PendingDisposition;
import com.fintech.creditportfolio.application.port.out.PortfolioSelfCheckQueries.SnapshotGap;
import com.fintech.creditportfolio.application.port.out.PortfolioSelfCheckQueries.UnfinishedCancellation;
import com.fintech.shared.selfcheck.SelfCheck;
import com.fintech.shared.selfcheck.SelfCheckReport;
import com.fintech.shared.time.BusinessClock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static com.fintech.shared.selfcheck.SelfCheckText.money;
import static com.fintech.shared.selfcheck.SelfCheckText.sample;

/**
 * The portfolio checks itself, on its own data: every account's debt is what its ledger of balance events adds up to,
 * every day's photo matched the ledger when it was taken, every past day closed clean, and no balance is below zero.
 * Dispositions still waiting for their payout's outcome days later are reported.
 */
@Service
@Transactional(readOnly = true)
public class PortfolioSelfCheckService {

    public static final String SERVICE = "credit-portfolio-service";
    /** SPEI answers in minutes: a disposition still in flight two days later lost its outcome. */
    private static final Duration NO_OUTCOME_AFTER = Duration.ofDays(2);

    private final PortfolioSelfCheckQueries queries;

    public PortfolioSelfCheckService(PortfolioSelfCheckQueries queries) {
        this.queries = queries;
    }

    /** The checks of a month's day photos (YYYYMM), or of every day with none; the accounts are checked as of now. */
    public SelfCheckReport check(String period) {
        Instant now = BusinessClock.current().now();
        LocalDate today = BusinessClock.current().today();
        List<SelfCheck> checks = new ArrayList<>();
        checks.add(debtIsItsLedger());
        checks.add(photosMatchedTheLedger(period));
        checks.add(pastDaysClosedClean(today));
        checks.add(noBalanceBelowZero());
        checks.add(dispositionsWithoutOutcome(now));
        checks.add(cancellationsFinished());
        return SelfCheckReport.of(SERVICE, period, now, checks);
    }

    /**
     * A credit cancelled because its disbursement never happened (D63) owes nothing and has no live plan: the
     * principal came back with the failure, and the opening fee and its IVA with charges' reversal. What is left is a
     * compensation that did not finish.
     */
    private SelfCheck cancellationsFinished() {
        int cancelled = queries.cancelledAccounts();
        List<UnfinishedCancellation> open = queries.unfinishedCancellations();
        BigDecimal debt = open.stream().map(UnfinishedCancellation::debt).reduce(BigDecimal.ZERO, BigDecimal::add);
        return SelfCheck.mustHold("CANCELADAS_EN_CERO", "Cada crédito cancelado por un desembolso fallido quedó en cero",
                open.size(), debt,
                open.isEmpty()
                        ? (cancelled == 0 ? "Ningún crédito cancelado"
                                : cancelled + " créditos cancelados, todos en cero y sin cuotas vivas")
                        : open.size() + " de " + cancelled + " créditos cancelados no terminaron su compensación ("
                                + sample(open.stream().map(c -> c.contractNumber() + ": debe " + money(c.debt())
                                        + ", " + c.liveInstallments() + " cuotas vivas").toList()) + ")");
    }

    private SelfCheck debtIsItsLedger() {
        Book book = queries.book();
        List<LedgerGap> gaps = queries.ledgerGaps();
        BigDecimal diff = gaps.stream().map(g -> g.debt().subtract(g.ledger()).abs())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return SelfCheck.mustHold("SALDO_IGUAL_A_SU_LIBRO", "La deuda de cada cuenta es la suma de sus movimientos",
                gaps.size(), diff,
                gaps.isEmpty()
                        ? book.accounts() + " cuentas por " + money(book.debt()) + ": cada una es la suma de sus movimientos"
                        : gaps.size() + " de " + book.accounts() + " cuentas no son la suma de sus movimientos ("
                                + sample(gaps.stream().map(g -> g.contractNumber() + ": saldo " + money(g.debt())
                                        + ", movimientos " + money(g.ledger())).toList()) + ")");
    }

    private SelfCheck photosMatchedTheLedger(String period) {
        int photos = queries.snapshots(period);
        List<SnapshotGap> gaps = queries.snapshotGaps(period);
        BigDecimal diff = gaps.stream().map(g -> g.gap().abs()).reduce(BigDecimal.ZERO, BigDecimal::add);
        return SelfCheck.mustHold("FOTO_SIN_BRECHA", "Cada foto del día coincidió con el libro de la cuenta", gaps.size(),
                diff,
                gaps.isEmpty()
                        ? photos + " fotos diarias, todas iguales al libro de su cuenta"
                        : gaps.size() + " de " + photos + " fotos con brecha (" + sample(gaps.stream()
                                .map(g -> g.businessDate() + " " + g.creditAccountId().toString().substring(0, 8) + ": "
                                        + money(g.gap())).toList()) + ")");
    }

    private SelfCheck pastDaysClosedClean(LocalDate today) {
        List<OpenClose> open = queries.openClosesBefore(today);
        return SelfCheck.mustHold("CIERRES_DEL_DIA_TERMINADOS", "Cada día pasado cerró completo y sin errores",
                open.size(), BigDecimal.ZERO,
                open.isEmpty()
                        ? queries.dayCloses(today) + " cierres del día, todos terminados sin errores"
                        : open.size() + " días sin cerrar limpio (" + sample(open.stream()
                                .map(c -> c.businessDate() + " " + c.status() + (c.errors() > 0 ? ", " + c.errors()
                                        + " errores" : "") + (c.lastError() == null ? "" : ": " + c.lastError())).toList())
                                + ")");
    }

    private SelfCheck noBalanceBelowZero() {
        List<NegativeBalance> negative = queries.negativeBalances();
        return SelfCheck.mustHold("SALDOS_NO_NEGATIVOS", "Ningún saldo de una cuenta está por debajo de cero",
                negative.size(), BigDecimal.ZERO,
                negative.isEmpty()
                        ? "Capital, interés, IVA y moratorios en cero o arriba en todas las cuentas: lo pagado de más es "
                                + "saldo a favor, no un saldo negativo"
                        : negative.size() + " cuentas con un saldo negativo (" + sample(negative.stream()
                                .map(n -> n.contractNumber() + ": capital " + money(n.principal()) + ", interés "
                                        + money(n.interest()) + ", IVA " + money(n.iva()) + ", moratorios "
                                        + money(n.penalty())).toList()) + ")");
    }

    private SelfCheck dispositionsWithoutOutcome(Instant now) {
        List<PendingDisposition> pending = queries.dispositionsInFlightBefore(now.minus(NO_OUTCOME_AFTER));
        BigDecimal amount = pending.stream().map(PendingDisposition::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        return SelfCheck.informs("DISPOSICIONES_SIN_DESENLACE", "Disposiciones solicitadas hace más de dos días sin desenlace",
                pending.size(), amount,
                pending.isEmpty()
                        ? "Ninguna disposición espera el desenlace de su pago desde hace días"
                        : pending.size() + " disposiciones esperan su pago por " + money(amount) + " (" + sample(pending
                                .stream().map(d -> d.dispositionId().toString().substring(0, 8) + " "
                                        + money(d.amount())).toList()) + "): revisar su orden en tesorería");
    }
}

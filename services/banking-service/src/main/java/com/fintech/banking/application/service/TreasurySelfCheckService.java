package com.fintech.banking.application.service;

import com.fintech.banking.application.port.out.TreasurySelfCheckQueries;
import com.fintech.banking.application.port.out.TreasurySelfCheckQueries.Accrual;
import com.fintech.banking.application.port.out.TreasurySelfCheckQueries.CashDay;
import com.fintech.banking.application.port.out.TreasurySelfCheckQueries.FundingFigures;
import com.fintech.banking.application.port.out.TreasurySelfCheckQueries.PayoutMismatch;
import com.fintech.banking.application.port.out.TreasurySelfCheckQueries.StaleTrace;
import com.fintech.shared.selfcheck.SelfCheck;
import com.fintech.shared.selfcheck.SelfCheckReport;
import com.fintech.shared.time.BusinessClock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static com.fintech.shared.selfcheck.SelfCheckText.money;
import static com.fintech.shared.selfcheck.SelfCheckText.sample;

/**
 * Treasury checks itself, on its own data: each funding source holds what its movements add up to, each interest
 * accrual is what its rate and window give and no day accrued twice or never, every settled order is in the cash book
 * and in its payout trace for the same amount, and the cash never closed a day below zero. A payout returned before it
 * settled is in neither: the money never left. Payout traces that stopped before any outcome are reported. A fact of
 * the bank twice in the cash book is not checked here: the database refuses it (one movement per event and per
 * tracking key).
 */
@Service
@Transactional(readOnly = true)
public class TreasurySelfCheckService {

    public static final String SERVICE = "banking-service";
    private static final BigDecimal TOLERANCE = new BigDecimal("0.005");
    /** SPEI answers in minutes: a payout still routed or dispatched two days after its day lost its outcome. */
    private static final int STALE_DAYS = 2;

    private final TreasurySelfCheckQueries queries;

    public TreasurySelfCheckService(TreasurySelfCheckQueries queries) {
        this.queries = queries;
    }

    public SelfCheckReport check() {
        LocalDate today = BusinessClock.current().today();
        List<SelfCheck> checks = new ArrayList<>();
        checks.add(fundingAddsUp());
        checks.add(fundingInterestIsItsRate());
        checks.add(settledOrdersInCashAndTrace());
        checks.add(cashNeverBelowZero());
        checks.add(tracesWithoutOutcome(today));
        return SelfCheckReport.of(SERVICE, SelfCheckReport.EVERYTHING, BusinessClock.current().now(), checks);
    }

    private SelfCheck fundingAddsUp() {
        List<FundingFigures> sources = queries.funding();
        List<String> off = new ArrayList<>();
        BigDecimal diff = BigDecimal.ZERO;
        for (FundingFigures f : sources) {
            BigDecimal d = f.contributed().subtract(f.contributions()).abs()
                    .add(f.principal().subtract(f.drawnLessRepaid()).abs())
                    .add(f.interestPayable().subtract(f.accruedLessPaid()).abs());
            if (d.compareTo(TOLERANCE) > 0) {
                diff = diff.add(d);
                off.add(f.kind() + " " + f.counterparty() + ": aportado " + money(f.contributed()) + " / "
                        + money(f.contributions()) + ", capital " + money(f.principal()) + " / "
                        + money(f.drawnLessRepaid()) + ", intereses " + money(f.interestPayable()) + " / "
                        + money(f.accruedLessPaid()));
            }
        }
        return SelfCheck.mustHold("FONDEO_CUADRA", "Cada fuente de fondeo es la suma de sus movimientos", off.size(), diff,
                off.isEmpty()
                        ? sources.size() + " fuentes: aportado " + money(sum(sources, FundingFigures::contributed))
                                + ", capital dispuesto " + money(sum(sources, FundingFigures::principal))
                                + ", intereses por pagar " + money(sum(sources, FundingFigures::interestPayable))
                        : off.size() + " fuentes no son la suma de sus movimientos (" + sample(off) + ")");
    }

    private SelfCheck fundingInterestIsItsRate() {
        List<Accrual> accruals = queries.accruals();
        List<String> off = new ArrayList<>();
        BigDecimal diff = BigDecimal.ZERO;
        for (Accrual a : accruals) {
            BigDecimal d = a.amount().subtract(a.expected()).abs();
            boolean contiguous = a.previousThrough() == null || a.from().equals(a.previousThrough().plusDays(1));
            if (d.compareTo(TOLERANCE) > 0 || !contiguous) {
                diff = diff.add(d);
                off.add(a.from() + " a " + a.through() + ": " + money(a.amount()) + " por " + money(a.expected())
                        + (contiguous ? "" : ", la ventana anterior cerró el " + a.previousThrough()));
            }
        }
        return SelfCheck.mustHold("INTERES_DEL_FONDEO",
                "Intereses del fondeo: días reales sobre 360, cada día una sola vez", off.size(), diff,
                off.isEmpty()
                        ? accruals.size() + " devengos por " + money(sum(accruals, Accrual::amount))
                                + ", cada uno a su tasa y sin días repetidos ni saltados"
                        : off.size() + " devengos fuera de su tasa o de su ventana (" + sample(off) + ")");
    }

    private SelfCheck settledOrdersInCashAndTrace() {
        List<PayoutMismatch> off = queries.payoutMismatches();
        int orders = queries.settledOrders();
        BigDecimal diff = off.stream()
                .map(m -> orZero(m.cash()).subtract(orZero(m.trace())).abs())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return SelfCheck.mustHold("PAGOS_EN_CAJA_Y_TRAZA",
                "Cada orden liquidada está en el libro de caja y en su traza, por el mismo importe", off.size(), diff,
                off.isEmpty()
                        ? orders + " órdenes liquidadas: libro de caja y traza coinciden una por una"
                        : off.size() + " órdenes en un solo lado o con importes distintos (" + sample(off.stream()
                                .map(m -> m.trackingKey() + ": caja " + (m.cash() == null ? "—" : money(m.cash()))
                                        + ", traza " + (m.trace() == null ? "—" : money(m.trace()))).toList()) + ")");
    }

    private SelfCheck cashNeverBelowZero() {
        List<CashDay> negative = queries.negativeDays();
        BigDecimal worst = negative.stream().map(CashDay::balance).reduce(BigDecimal.ZERO, BigDecimal::min);
        return SelfCheck.mustHold("CAJA_NO_NEGATIVA", "La caja nunca cierra un día en negativo", negative.size(),
                worst.negate(),
                negative.isEmpty()
                        ? queries.cashDays() + " días con movimiento, ninguno cierra en negativo"
                        : negative.size() + " días cierran en negativo (" + sample(negative.stream()
                                .map(d -> d.businessDate() + ": " + money(d.balance())).toList()) + ")");
    }

    private SelfCheck tracesWithoutOutcome(LocalDate today) {
        List<StaleTrace> stale = queries.tracesStoppedBefore(today.minusDays(STALE_DAYS));
        return SelfCheck.informs("TRAZAS_SIN_DESENLACE", "Pagos enviados hace más de dos días sin desenlace",
                stale.size(), sum(stale, StaleTrace::amount),
                stale.isEmpty()
                        ? "Ningún pago espera su desenlace desde hace días"
                        : stale.size() + " pagos detenidos (" + sample(stale.stream()
                                .map(t -> t.payoutId().toString().substring(0, 8) + " " + t.status() + " del "
                                        + t.businessDate() + " por " + money(t.amount())).toList()) + ")");
    }

    private static <T> BigDecimal sum(List<T> rows, Function<T, BigDecimal> value) {
        return rows.stream().map(value).map(TreasurySelfCheckService::orZero).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal orZero(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}

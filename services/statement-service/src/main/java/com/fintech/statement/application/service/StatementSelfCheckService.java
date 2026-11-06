package com.fintech.statement.application.service;

import com.fintech.shared.selfcheck.SelfCheck;
import com.fintech.shared.selfcheck.SelfCheckReport;
import com.fintech.shared.time.BusinessClock;
import com.fintech.statement.application.port.out.StatementSelfCheckQueries;
import com.fintech.statement.application.port.out.StatementSelfCheckQueries.BalanceGap;
import com.fintech.statement.application.port.out.StatementSelfCheckQueries.MissingDocument;
import com.fintech.statement.application.port.out.StatementSelfCheckQueries.RunGap;
import com.fintech.statement.application.port.out.StatementSelfCheckQueries.Ungenerated;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static com.fintech.shared.selfcheck.SelfCheckText.money;
import static com.fintech.shared.selfcheck.SelfCheckText.sample;

/**
 * The statements check themselves, on their own data: every sealed cutoff run has one statement per account it closed,
 * every statement shows the balance its cutoff sealed and has its document, and none is left ungenerated once its
 * cutoff is past. Whether a statement adds up against the portfolio's ledger is the chain's audit (row 06): this service
 * does not hold that ledger.
 */
@Service
@Transactional(readOnly = true)
public class StatementSelfCheckService {

    public static final String SERVICE = "statement-service";
    /** A statement is issued the day after its cutoff; a day more and it is late. */
    private static final int LATE_AFTER_DAYS = 1;

    private final StatementSelfCheckQueries queries;

    public StatementSelfCheckService(StatementSelfCheckQueries queries) {
        this.queries = queries;
    }

    public SelfCheckReport check() {
        LocalDate today = BusinessClock.current().today();
        List<SelfCheck> checks = new ArrayList<>();
        checks.add(oneStatementPerClosedAccount());
        checks.add(statementShowsItsCutoffBalance());
        checks.add(everyStatementHasItsDocument());
        checks.add(noneLeftUngenerated(today));
        return SelfCheckReport.of(SERVICE, SelfCheckReport.EVERYTHING, BusinessClock.current().now(), checks);
    }

    private SelfCheck oneStatementPerClosedAccount() {
        int runs = queries.sealedRuns();
        List<RunGap> gaps = queries.runGaps();
        return SelfCheck.mustHold("UN_ESTADO_POR_CUENTA_CERRADA", "Cada corte sellado tiene un estado por cuenta que cerró",
                gaps.size(), BigDecimal.ZERO,
                gaps.isEmpty()
                        ? runs + " corridas de corte selladas, cada una con un estado por cuenta cerrada"
                        : gaps.size() + " corridas con estados de más o de menos (" + sample(gaps.stream()
                                .map(g -> g.businessDate() + ": " + g.closed() + " cuentas cerradas, " + g.statements()
                                        + " estados").toList()) + ")");
    }

    private SelfCheck statementShowsItsCutoffBalance() {
        List<BalanceGap> gaps = queries.balanceGaps();
        BigDecimal diff = gaps.stream().map(g -> g.balance().subtract(g.cutoffBalance()).abs())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return SelfCheck.mustHold("SALDO_DEL_CORTE", "Cada estado muestra el saldo que selló su corte", gaps.size(), diff,
                gaps.isEmpty()
                        ? queries.statements() + " estados con el saldo de su corte"
                        : gaps.size() + " estados con otro saldo que el de su corte (" + sample(gaps.stream()
                                .map(g -> g.contractNumber() + " " + g.cutoffDate() + ": " + money(g.balance())
                                        + " por " + money(g.cutoffBalance())).toList()) + ")");
    }

    private SelfCheck everyStatementHasItsDocument() {
        List<MissingDocument> missing = queries.missingDocuments();
        return SelfCheck.mustHold("DOCUMENTO_DE_CADA_ESTADO", "Cada estado generado tiene su documento íntegro",
                missing.size(), BigDecimal.ZERO,
                missing.isEmpty()
                        ? "Cada estado generado tiene su PDF guardado, con su huella y el tamaño que registró"
                        : missing.size() + " estados sin documento o con otro tamaño (" + sample(missing.stream()
                                .map(m -> m.contractNumber() + ": registró " + m.documentBytes() + " bytes, guardado "
                                        + m.storedBytes()).toList()) + ")");
    }

    private SelfCheck noneLeftUngenerated(LocalDate today) {
        List<Ungenerated> late = queries.ungeneratedBefore(today.minusDays(LATE_AFTER_DAYS));
        return SelfCheck.mustHold("ESTADOS_GENERADOS", "Ningún estado de un corte pasado queda sin generar", late.size(),
                BigDecimal.ZERO,
                late.isEmpty()
                        ? "Todo estado de un corte pasado está generado"
                        : late.size() + " estados sin generar (" + sample(late.stream()
                                .map(u -> u.contractNumber() + " " + u.cutoffDate() + " " + u.status() + " tras "
                                        + u.attempts() + " intentos" + (u.lastError() == null ? "" : ": " + u.lastError()))
                                .toList()) + ")");
    }
}

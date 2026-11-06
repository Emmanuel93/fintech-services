package com.fintech.accounting.infrastructure.adapter.out.persistence;

import com.fintech.accounting.application.CloseReport.CloseCheck;
import com.fintech.accounting.application.port.out.CloseReconciliationQueries;
import com.fintech.accounting.application.port.out.CloseReconciliationQueries.YearBalance;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The month-end checks in SQL, over accounting's own tables.
 *
 * <p>The ledger is compared, account by account, with what the subledgers say: the portfolio's balances as accounting
 * last saw them (the shadow, written with the absolute balances each fact carries, while the ledger adds up the
 * entries the posting rules made of the same facts), the reserve risk measured, and the deferred fees' schedules. They
 * are compared as of the moment of the close: the ledger of every period against the balances of now. A fact of the
 * next month already booked is in both; the month's own figures are right as long as each entry is in the period of
 * its fact, which is how vouchers are dated.
 */
@Repository
class JdbcCloseReconciliationDao implements CloseReconciliationQueries {

    private static final BigDecimal TOLERANCE = new BigDecimal("0.005");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.forLanguageTag("es-MX"));

    private final NamedParameterJdbcTemplate jdbc;
    private final ClearingAccountChecks clearing;
    private final CommissionPayableChecks commissions;
    private final ProductChecks products;

    JdbcCloseReconciliationDao(NamedParameterJdbcTemplate jdbc, ClearingAccountChecks clearing,
                               CommissionPayableChecks commissions, ProductChecks products) {
        this.jdbc = jdbc;
        this.clearing = clearing;
        this.commissions = commissions;
        this.products = products;
    }

    @Override
    public List<CloseCheck> reconcile(String period, LocalDate lastDay, Instant lastDayStart, boolean amortized) {
        MapSqlParameterSource p = new MapSqlParameterSource("period", period).addValue("lastDay", lastDay)
                .addValue("lastDayStart", Timestamp.from(lastDayStart));
        List<CloseCheck> checks = new ArrayList<>();
        checks.add(trialBalanceBalances(p));
        checks.add(accountingEquation(p));
        checks.add(subledgerClosed(p, lastDay));
        checks.add(allowanceMeasured(p, lastDay));
        checks.add(vouchersBalance(p));
        checks.addAll(portfolioVsLedger());
        checks.add(allowanceVsLedger());
        checks.addAll(products.all());
        checks.add(deferredFeesVsLedger());
        checks.add(originationCostsVsLedger());
        checks.add(feesAmortized(p, amortized));
        checks.add(orderAccounts());
        checks.add(noIncorporations());
        checks.add(centavos(p));
        checks.add(fundingAccrued(p, lastDay));
        checks.add(fundingVsLedger());
        checks.add(fundingCostsVsLedger());
        checks.add(bankNotNegative(p));
        checks.add(latePostings(p));
        checks.add(treasuryVsLedger(p));
        checks.addAll(clearing.all(period));
        checks.add(commissions.commissionsPayable(period));
        checks.add(billingRequested(p));
        checks.add(sofomRequirement(p));
        return checks;
    }

    @Override
    public List<YearBalance> yearResult(int year) {
        return jdbc.query("""
                SELECT e.org_unit_code, e.product_type, a.code,
                       SUM(CASE WHEN e.debit_account = a.code THEN e.amount ELSE 0 END)
                     - SUM(CASE WHEN e.credit_account = a.code THEN e.amount ELSE 0 END) AS balance
                  FROM accounting.ledger_accounts a
                  JOIN accounting.journal_entries e ON a.code IN (e.debit_account, e.credit_account)
                 WHERE a.type IN ('INCOME', 'EXPENSE') AND e.period BETWEEN :from AND :to
                 GROUP BY e.org_unit_code, e.product_type, a.code
                HAVING SUM(CASE WHEN e.debit_account = a.code THEN e.amount ELSE 0 END)
                     <> SUM(CASE WHEN e.credit_account = a.code THEN e.amount ELSE 0 END)
                 ORDER BY e.org_unit_code NULLS FIRST, e.product_type NULLS FIRST, a.code""",
                new MapSqlParameterSource("from", year + "01").addValue("to", year + "12"),
                (rs, i) -> new YearBalance(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBigDecimal(4)));
    }

    /**
     * The trial balance at the month's end: the debit balances add up to the credit balances, and the month's debits
     * to its credits. True by construction (every entry is a pair); shown so the balance is seen, not assumed.
     */
    private CloseCheck trialBalanceBalances(MapSqlParameterSource p) {
        Map<String, Object> r = jdbc.queryForMap("""
                WITH b AS (
                    SELECT a.code,
                           SUM(CASE WHEN e.debit_account = a.code THEN e.amount ELSE 0 END)
                         - SUM(CASE WHEN e.credit_account = a.code THEN e.amount ELSE 0 END) AS balance,
                           SUM(CASE WHEN e.debit_account = a.code AND e.period = :period THEN e.amount ELSE 0 END) AS debits,
                           SUM(CASE WHEN e.credit_account = a.code AND e.period = :period THEN e.amount ELSE 0 END) AS credits
                      FROM accounting.ledger_accounts a
                      JOIN accounting.journal_entries e ON a.code IN (e.debit_account, e.credit_account)
                     WHERE e.period <= :period AND a.type <> 'ORDER'
                     GROUP BY a.code)
                SELECT COALESCE(SUM(balance) FILTER (WHERE balance > 0), 0) AS debit_balances,
                       COALESCE(-SUM(balance) FILTER (WHERE balance < 0), 0) AS credit_balances,
                       COALESCE(SUM(debits), 0) AS debits, COALESCE(SUM(credits), 0) AS credits
                  FROM b""", p);
        BigDecimal debitBalances = dec(r.get("debit_balances")), creditBalances = dec(r.get("credit_balances"));
        BigDecimal debits = dec(r.get("debits")), credits = dec(r.get("credits"));
        BigDecimal gap = debitBalances.subtract(creditBalances).abs().add(debits.subtract(credits).abs());
        boolean ok = gap.compareTo(TOLERANCE) <= 0;
        return new CloseCheck("BALANZA_CUADRA", "Balanza de comprobación cuadrada", ok, true, ok ? 0 : 1, gap,
                "saldos deudores " + money(debitBalances) + (ok ? " = " : " ≠ ") + "saldos acreedores "
                + money(creditBalances) + "; cargos del mes " + money(debits) + " = abonos " + money(credits));
    }

    /** Assets = liabilities + equity + the result of the year (revenue − expenses), at the month's end. */
    private CloseCheck accountingEquation(MapSqlParameterSource p) {
        Map<String, Object> r = jdbc.queryForMap("""
                WITH b AS (
                    SELECT a.type,
                           SUM(CASE WHEN e.debit_account = a.code THEN e.amount ELSE 0 END)
                         - SUM(CASE WHEN e.credit_account = a.code THEN e.amount ELSE 0 END) AS balance
                      FROM accounting.ledger_accounts a
                      JOIN accounting.journal_entries e ON a.code IN (e.debit_account, e.credit_account)
                     WHERE e.period <= :period
                     GROUP BY a.code, a.type)
                SELECT COALESCE(SUM(balance) FILTER (WHERE type IN ('ASSET', 'CONTRA_ASSET')), 0) AS assets,
                       COALESCE(-SUM(balance) FILTER (WHERE type = 'LIABILITY'), 0) AS liabilities,
                       COALESCE(-SUM(balance) FILTER (WHERE type = 'EQUITY'), 0) AS equity,
                       COALESCE(-SUM(balance) FILTER (WHERE type IN ('INCOME', 'EXPENSE')), 0) AS result
                  FROM b""", p);
        BigDecimal assets = dec(r.get("assets")), liabilities = dec(r.get("liabilities")),
                equity = dec(r.get("equity")), result = dec(r.get("result"));
        BigDecimal gap = assets.subtract(liabilities.add(equity).add(result)).abs();
        boolean ok = gap.compareTo(TOLERANCE) <= 0;
        return new CloseCheck("ECUACION_CONTABLE", "Ecuación contable: activo = pasivo + capital + resultado", ok, true,
                ok ? 0 : 1, gap, "activo " + money(assets) + (ok ? " = " : " ≠ ") + "pasivo " + money(liabilities)
                + " + capital " + money(equity) + " + resultado " + money(result));
    }

    /**
     * Everything the month charged the clients went into an invoice request (the CFDI is stamped by invoicing).
     * Reported: stamping happens after the billing run, and the month is the books', not the tax authority's.
     */
    private CloseCheck billingRequested(MapSqlParameterSource p) {
        Map<String, Object> r = jdbc.queryForMap("""
                SELECT count(*) AS items, COALESCE(SUM(amount), 0) AS charged,
                       count(*) FILTER (WHERE status <> 'BILLED') AS pending,
                       COALESCE(SUM(amount) FILTER (WHERE status <> 'BILLED'), 0) AS pending_amount
                  FROM accounting.invoiceable_items WHERE period = :period""", p);
        int pending = (int) num(r.get("pending"));
        return new CloseCheck("FACTURACION_SOLICITADA", "Lo cobrado del mes, enviado a facturar", pending == 0, false,
                pending, dec(r.get("pending_amount")), pending == 0
                        ? "cobrado al cliente " + money(dec(r.get("charged"))) + ", todo en solicitudes de CFDI"
                        : pending + " cargos por " + money(dec(r.get("pending_amount"))) + " sin solicitud de CFDI");
    }

    /** Every funding line with principal drawn has its interest accrued through the month's last day. */
    private CloseCheck fundingAccrued(MapSqlParameterSource p, LocalDate lastDay) {
        Map<String, Object> r = jdbc.queryForMap("""
                SELECT count(*) AS lines,
                       count(*) FILTER (WHERE accrued_through IS NULL OR accrued_through < :lastDay) AS behind,
                       COALESCE(string_agg(counterparty_name, ', ') FILTER (WHERE accrued_through IS NULL
                                                                         OR accrued_through < :lastDay), '') AS who
                  FROM accounting.funding_balances
                 WHERE kind = 'CREDIT_LINE' AND status = 'ACTIVE' AND principal > 0""", p);
        int lines = (int) num(r.get("lines")), behind = (int) num(r.get("behind"));
        String day = DAY.format(lastDay);
        return new CloseCheck("FONDEO_DEVENGADO", "Intereses del fondeo devengados al último día", behind == 0, true,
                behind, null, lines == 0 ? "sin líneas de fondeo dispuestas"
                        : behind == 0 ? lines + " líneas con sus intereses devengados al " + day
                        : behind + " de " + lines + " líneas sin devengar al " + day + ": " + r.get("who"));
    }

    /** 2201, 2202 and 3101 against what treasury says each funding source holds. */
    private CloseCheck fundingVsLedger() {
        Map<String, Object> r = jdbc.queryForMap("""
                SELECT (SELECT COALESCE(SUM(principal), 0) FROM accounting.funding_balances) AS principal,
                       (SELECT COALESCE(SUM(interest_payable), 0) FROM accounting.funding_balances) AS interest,
                       (SELECT COALESCE(SUM(contributed), 0) FROM accounting.funding_balances) AS capital,
                       (SELECT COALESCE(SUM(CASE WHEN credit_account = '2201' THEN amount ELSE 0 END)
                             - SUM(CASE WHEN debit_account = '2201' THEN amount ELSE 0 END), 0)
                          FROM accounting.journal_entries WHERE '2201' IN (debit_account, credit_account)) AS gl_principal,
                       (SELECT COALESCE(SUM(CASE WHEN credit_account = '2202' THEN amount ELSE 0 END)
                             - SUM(CASE WHEN debit_account = '2202' THEN amount ELSE 0 END), 0)
                          FROM accounting.journal_entries WHERE '2202' IN (debit_account, credit_account)) AS gl_interest,
                       (SELECT COALESCE(SUM(CASE WHEN credit_account = '3101' THEN amount ELSE 0 END)
                             - SUM(CASE WHEN debit_account = '3101' THEN amount ELSE 0 END), 0)
                          FROM accounting.journal_entries WHERE '3101' IN (debit_account, credit_account)) AS gl_capital""",
                new MapSqlParameterSource());
        BigDecimal dp = dec(r.get("principal")).subtract(dec(r.get("gl_principal"))).abs();
        BigDecimal di = dec(r.get("interest")).subtract(dec(r.get("gl_interest"))).abs();
        BigDecimal dc = dec(r.get("capital")).subtract(dec(r.get("gl_capital"))).abs();
        boolean ok = dp.compareTo(TOLERANCE) <= 0 && di.compareTo(TOLERANCE) <= 0 && dc.compareTo(TOLERANCE) <= 0;
        String detail = "capital 3101 " + money(dec(r.get("gl_capital"))) + (dc.signum() == 0 ? "" : " (tesorería " + money(dec(r.get("capital"))) + ")")
                + "; préstamos 2201 " + money(dec(r.get("gl_principal"))) + (dp.signum() == 0 ? "" : " (tesorería " + money(dec(r.get("principal"))) + ")")
                + "; intereses por pagar 2202 " + money(dec(r.get("gl_interest"))) + (di.signum() == 0 ? "" : " (tesorería " + money(dec(r.get("interest"))) + ")");
        return new CloseCheck("FONDEO_MAYOR", "Fondeo: tesorería contra el mayor 3101, 2201 y 2202", ok, true,
                ok ? 0 : 1, dp.add(di).add(dc), detail);
    }

    /** 2209 against what the funding lines' opening fees still have to amortize. */
    private CloseCheck fundingCostsVsLedger() {
        Map<String, Object> r = jdbc.queryForMap("""
                SELECT (SELECT COALESCE(SUM(total - recognized), 0) FROM accounting.deferred_fees WHERE kind = 'FUNDING') AS sub,
                       (SELECT COALESCE(SUM(CASE WHEN debit_account = '2209' THEN amount ELSE 0 END)
                             - SUM(CASE WHEN credit_account = '2209' THEN amount ELSE 0 END), 0)
                          FROM accounting.journal_entries WHERE '2209' IN (debit_account, credit_account)) AS gl""",
                new MapSqlParameterSource());
        BigDecimal sub = dec(r.get("sub")), gl = dec(r.get("gl"));
        BigDecimal gap = sub.subtract(gl).abs();
        boolean ok = gap.compareTo(TOLERANCE) <= 0;
        return new CloseCheck("COSTOS_FONDEO_2209", "Costos de transacción del fondeo: calendario contra el mayor 2209",
                ok, true, ok ? 0 : 1, gap, "calendario " + money(sub) + (ok ? " = " : ", ") + "mayor " + money(gl));
    }

    /**
     * Banks cannot hold less than nothing: a negative 1101 at the month's end means money went out that the books
     * never saw come in — funding not recorded, or an inflow missing.
     */
    private CloseCheck bankNotNegative(MapSqlParameterSource p) {
        BigDecimal balance = jdbc.queryForObject("""
                SELECT COALESCE(SUM(CASE WHEN debit_account = '1101' THEN amount ELSE 0 END)
                              - SUM(CASE WHEN credit_account = '1101' THEN amount ELSE 0 END), 0)
                  FROM accounting.journal_entries
                 WHERE (debit_account = '1101' OR credit_account = '1101') AND period <= :period""", p, BigDecimal.class);
        boolean ok = balance.compareTo(BigDecimal.ZERO.subtract(TOLERANCE)) >= 0;
        return new CloseCheck("BANCOS_NO_NEGATIVO", "Bancos 1101 sin saldo negativo al cierre", ok, true, ok ? 0 : 1,
                ok ? BigDecimal.ZERO : balance.abs(), ok ? "saldo en libros al cierre " + money(balance)
                        : "1101 en " + money(balance) + " al cierre: salió dinero que los libros nunca vieron entrar "
                          + "(falta registrar el fondeo o un ingreso)");
    }

    /**
     * LGOAAC 87-B: to be a SOFOM, credit (with leasing and factoring) must be at least 70 % of total assets and its
     * revenue at least 70 % of total revenue. Measured on the year's statements; reported here month by month.
     */
    private CloseCheck sofomRequirement(MapSqlParameterSource p) {
        Map<String, Object> r = jdbc.queryForMap("""
                WITH b AS (
                    SELECT a.code, a.type,
                           COALESCE(SUM(CASE WHEN e.debit_account = a.code THEN e.amount ELSE 0 END)
                                  - SUM(CASE WHEN e.credit_account = a.code THEN e.amount ELSE 0 END), 0) AS balance,
                           COALESCE(SUM(CASE WHEN e.credit_account = a.code AND substr(e.period, 1, 4) = substr(:period, 1, 4)
                                             THEN e.amount ELSE 0 END)
                                  - SUM(CASE WHEN e.debit_account = a.code AND substr(e.period, 1, 4) = substr(:period, 1, 4)
                                             THEN e.amount ELSE 0 END), 0) AS revenue_ytd
                      FROM accounting.ledger_accounts a
                      JOIN accounting.journal_entries e ON a.code IN (e.debit_account, e.credit_account)
                     WHERE e.period <= :period
                     GROUP BY a.code, a.type)
                SELECT COALESCE(SUM(balance) FILTER (WHERE type IN ('ASSET', 'CONTRA_ASSET')), 0) AS assets,
                       COALESCE(SUM(balance) FILTER (WHERE code IN ('1201', '1203', '1205', '1290')), 0) AS portfolio,
                       COALESCE(SUM(revenue_ytd) FILTER (WHERE type = 'INCOME'), 0) AS revenue,
                       COALESCE(SUM(revenue_ytd) FILTER (WHERE code IN ('4101', '4102', '4103', '4106')), 0) AS credit_revenue
                  FROM b""", p);
        BigDecimal assets = dec(r.get("assets")), portfolio = dec(r.get("portfolio"));
        BigDecimal revenue = dec(r.get("revenue")), creditRevenue = dec(r.get("credit_revenue"));
        BigDecimal assetShare = share(portfolio, assets), revenueShare = share(creditRevenue, revenue);
        BigDecimal floor = new BigDecimal("0.70");
        boolean ok = assetShare.compareTo(floor) >= 0 && revenueShare.compareTo(floor) >= 0;
        return new CloseCheck("REQUISITO_SOFOM", "Requisito de SOFOM (LGOAAC 87-B): cartera y sus ingresos al 70 %", ok,
                false, null, null, "cartera " + pct(assetShare) + " de los activos (" + money(portfolio) + " de "
                        + money(assets) + "); ingresos por crédito " + pct(revenueShare) + " de los ingresos del año"
                        + (ok ? "" : ". Se mide con los estados financieros del ejercicio"));
    }

    private static BigDecimal share(BigDecimal part, BigDecimal whole) {
        return whole.signum() <= 0 ? BigDecimal.ZERO : part.divide(whole, 4, RoundingMode.HALF_UP);
    }

    private static String pct(BigDecimal share) {
        return share.movePointRight(2).setScale(1, RoundingMode.HALF_UP).toPlainString() + " %";
    }

    /** The portfolio closed the month's last day, and every account it held out of that day's accrual accrued it. */
    private CloseCheck subledgerClosed(MapSqlParameterSource p, LocalDate lastDay) {
        Map<String, Object> r = jdbc.queryForMap("""
                SELECT (SELECT count(*) FROM accounting.subledger_day_closes WHERE business_date = :lastDay) AS closed,
                       (SELECT count(*) FROM accounting.subledger_held_accounts
                         WHERE business_date = :lastDay AND released_at IS NULL) AS held""", p);
        boolean closed = num(r.get("closed")) > 0;
        int held = (int) num(r.get("held"));
        String day = DAY.format(lastDay);
        String detail = !closed
                ? "cartera no ha cerrado el " + day + ": faltan hechos del mes"
                : held > 0 ? "cartera cerró el " + day + " con " + held + " cuentas sin devengar el día"
                           : "cartera cerró el " + day + " con todas sus cuentas";
        return new CloseCheck("CARTERA_CERRADA", "Cartera cerró el último día del mes", closed && held == 0, true,
                closed ? held : null, null, detail);
    }

    /**
     * Every account with exposure in the month has its expected credit loss measured on the month's last day or later
     * (IFRS 9 §5.5: the allowance is measured at the reporting date). An account opened after the month is not asked.
     */
    private CloseCheck allowanceMeasured(MapSqlParameterSource p, LocalDate lastDay) {
        Map<String, Object> r = jdbc.queryForMap("""
                SELECT count(*) AS exposed,
                       count(*) FILTER (WHERE pl.measured_at IS NULL OR pl.measured_at < :lastDayStart) AS unmeasured,
                       COALESCE(SUM(s.total_debt) FILTER (WHERE pl.measured_at IS NULL OR pl.measured_at < :lastDayStart), 0) AS amount,
                       array_to_string((array_agg(s.credit_account_id::text ORDER BY s.total_debt DESC)
                                        FILTER (WHERE pl.measured_at IS NULL OR pl.measured_at < :lastDayStart))[1 : 5], ', ') AS sample
                  FROM accounting.account_balance_shadows s
                  LEFT JOIN accounting.provision_ledger pl ON pl.credit_account_id = s.credit_account_id
                 WHERE s.total_debt > 0.005
                   AND EXISTS (SELECT 1 FROM accounting.journal_entries e
                                WHERE e.credit_account_id = s.credit_account_id AND e.period <= :period)""", p);
        int exposed = (int) num(r.get("exposed"));
        int unmeasured = (int) num(r.get("unmeasured"));
        String detail = unmeasured == 0
                ? exposed + " créditos con saldo, todos con su reserva medida al " + DAY.format(lastDay)
                : unmeasured + " de " + exposed + " créditos sin reserva medida al " + DAY.format(lastDay)
                  + " (saldo " + money(dec(r.get("amount"))) + "): " + r.get("sample");
        return new CloseCheck("RESERVA_MEDIDA", "Reserva IFRS 9 medida a la fecha del cierre", unmeasured == 0, true,
                unmeasured, dec(r.get("amount")), detail);
    }

    /** Every voucher of the month balances, and its total is the sum of its entries. */
    private CloseCheck vouchersBalance(MapSqlParameterSource p) {
        Map<String, Object> r = jdbc.queryForMap("""
                WITH v AS (
                    SELECT v.voucher_id, v.total_debit, v.total_credit, COALESCE(SUM(e.amount), 0) AS lines
                      FROM accounting.vouchers v
                      LEFT JOIN accounting.journal_entries e ON e.voucher_id = v.voucher_id
                     WHERE v.period = :period AND v.status = 'POSTED'
                     GROUP BY v.voucher_id, v.total_debit, v.total_credit)
                SELECT count(*) AS vouchers,
                       count(*) FILTER (WHERE abs(total_debit - total_credit) > 0.005 OR abs(total_debit - lines) > 0.005) AS off,
                       COALESCE(SUM(lines), 0) AS total
                  FROM v""", p);
        int off = (int) num(r.get("off"));
        return new CloseCheck("POLIZAS_CUADRAN", "Pólizas del mes cuadradas", off == 0, true, off, null,
                off == 0 ? num(r.get("vouchers")) + " pólizas por " + money(dec(r.get("total"))) + ", todas cuadradas"
                         : off + " de " + num(r.get("vouchers")) + " pólizas no cuadran");
    }

    /** 1201 and 1203, account by account, against the portfolio's principal and receivable. */
    private List<CloseCheck> portfolioVsLedger() {
        Map<String, Object> r = jdbc.queryForMap("""
                WITH gl AS (
                    SELECT credit_account_id AS account,
                           SUM(CASE WHEN debit_account = '1201' THEN amount ELSE 0 END)
                         - SUM(CASE WHEN credit_account = '1201' THEN amount ELSE 0 END) AS principal,
                           SUM(CASE WHEN debit_account = '1203' THEN amount ELSE 0 END)
                         - SUM(CASE WHEN credit_account = '1203' THEN amount ELSE 0 END) AS receivable
                      FROM accounting.journal_entries
                     WHERE debit_account IN ('1201', '1203') OR credit_account IN ('1201', '1203')
                     GROUP BY credit_account_id),
                both_sides AS (
                    SELECT COALESCE(s.credit_account_id, gl.account) AS account,
                           COALESCE(s.principal_balance, 0) AS sub_principal, COALESCE(gl.principal, 0) AS gl_principal,
                           COALESCE(s.total_debt - s.principal_balance, 0) AS sub_receivable,
                           COALESCE(gl.receivable, 0) AS gl_receivable
                      FROM accounting.account_balance_shadows s
                      FULL OUTER JOIN gl ON gl.account = s.credit_account_id)
                SELECT count(*) AS accounts,
                       COALESCE(SUM(sub_principal), 0) AS sub_principal, COALESCE(SUM(gl_principal), 0) AS gl_principal,
                       count(*) FILTER (WHERE abs(sub_principal - gl_principal) > 0.005) AS principal_off,
                       COALESCE(SUM(abs(sub_principal - gl_principal)) FILTER (WHERE abs(sub_principal - gl_principal) > 0.005), 0) AS principal_diff,
                       array_to_string((array_agg(COALESCE(account::text, 'sin crédito') ORDER BY abs(sub_principal - gl_principal) DESC)
                                        FILTER (WHERE abs(sub_principal - gl_principal) > 0.005))[1 : 5], ', ') AS principal_sample,
                       COALESCE(SUM(sub_receivable), 0) AS sub_receivable, COALESCE(SUM(gl_receivable), 0) AS gl_receivable,
                       count(*) FILTER (WHERE abs(sub_receivable - gl_receivable) > 0.005) AS receivable_off,
                       COALESCE(SUM(abs(sub_receivable - gl_receivable)) FILTER (WHERE abs(sub_receivable - gl_receivable) > 0.005), 0) AS receivable_diff,
                       array_to_string((array_agg(COALESCE(account::text, 'sin crédito') ORDER BY abs(sub_receivable - gl_receivable) DESC)
                                        FILTER (WHERE abs(sub_receivable - gl_receivable) > 0.005))[1 : 5], ', ') AS receivable_sample
                  FROM both_sides""", new MapSqlParameterSource());
        int accounts = (int) num(r.get("accounts"));
        return List.of(
                compare("CAPITAL_1201", "Capital: cartera contra el mayor 1201", accounts,
                        (int) num(r.get("principal_off")), dec(r.get("principal_diff")), dec(r.get("sub_principal")),
                        dec(r.get("gl_principal")), (String) r.get("principal_sample"), "cartera"),
                compare("POR_COBRAR_1203", "Intereses, comisiones e IVA por cobrar: cartera contra el mayor 1203",
                        accounts, (int) num(r.get("receivable_off")), dec(r.get("receivable_diff")),
                        dec(r.get("sub_receivable")), dec(r.get("gl_receivable")),
                        (String) r.get("receivable_sample"), "cartera"));
    }

    /** 1290, account by account, against the reserve risk measured and accounting booked. */
    private CloseCheck allowanceVsLedger() {
        Map<String, Object> r = jdbc.queryForMap("""
                WITH gl AS (
                    SELECT credit_account_id AS account,
                           SUM(CASE WHEN credit_account = '1290' THEN amount ELSE 0 END)
                         - SUM(CASE WHEN debit_account = '1290' THEN amount ELSE 0 END) AS allowance
                      FROM accounting.journal_entries
                     WHERE debit_account = '1290' OR credit_account = '1290'
                     GROUP BY credit_account_id),
                both_sides AS (
                    SELECT COALESCE(p.credit_account_id, gl.account) AS account,
                           COALESCE(p.last_booked_provision, 0) AS sub, COALESCE(gl.allowance, 0) AS gl
                      FROM accounting.provision_ledger p FULL OUTER JOIN gl ON gl.account = p.credit_account_id)
                SELECT count(*) AS accounts, COALESCE(SUM(sub), 0) AS sub, COALESCE(SUM(gl), 0) AS gl,
                       count(*) FILTER (WHERE abs(sub - gl) > 0.005) AS off,
                       COALESCE(SUM(abs(sub - gl)) FILTER (WHERE abs(sub - gl) > 0.005), 0) AS diff,
                       array_to_string((array_agg(COALESCE(account::text, 'sin crédito') ORDER BY abs(sub - gl) DESC)
                                        FILTER (WHERE abs(sub - gl) > 0.005))[1 : 5], ', ') AS sample
                  FROM both_sides""", new MapSqlParameterSource());
        return compare("RESERVA_1290", "Estimación preventiva: reservas medidas contra el mayor 1290",
                (int) num(r.get("accounts")), (int) num(r.get("off")), dec(r.get("diff")), dec(r.get("sub")),
                dec(r.get("gl")), (String) r.get("sample"), "reservas");
    }

    /** 2150, account by account, against what the opening fees' schedules still defer. */
    private CloseCheck deferredFeesVsLedger() {
        Map<String, Object> r = jdbc.queryForMap("""
                WITH gl AS (
                    SELECT credit_account_id AS account,
                           SUM(CASE WHEN credit_account = '2150' THEN amount ELSE 0 END)
                         - SUM(CASE WHEN debit_account = '2150' THEN amount ELSE 0 END) AS pending
                      FROM accounting.journal_entries
                     WHERE debit_account = '2150' OR credit_account = '2150'
                     GROUP BY credit_account_id),
                schedules AS (
                    SELECT credit_account_id AS account, SUM(total - recognized) AS pending
                      FROM accounting.deferred_fees WHERE kind = 'FEE' GROUP BY credit_account_id),
                both_sides AS (
                    SELECT COALESCE(sc.account, gl.account) AS account,
                           COALESCE(sc.pending, 0) AS sub, COALESCE(gl.pending, 0) AS gl
                      FROM schedules sc FULL OUTER JOIN gl ON gl.account = sc.account)
                SELECT count(*) AS accounts, COALESCE(SUM(sub), 0) AS sub, COALESCE(SUM(gl), 0) AS gl,
                       count(*) FILTER (WHERE abs(sub - gl) > 0.005) AS off,
                       COALESCE(SUM(abs(sub - gl)) FILTER (WHERE abs(sub - gl) > 0.005), 0) AS diff,
                       array_to_string((array_agg(COALESCE(account::text, 'sin crédito') ORDER BY abs(sub - gl) DESC)
                                        FILTER (WHERE abs(sub - gl) > 0.005))[1 : 5], ', ') AS sample
                  FROM both_sides""", new MapSqlParameterSource());
        return compare("DIFERIDAS_2150", "Comisiones de apertura por devengar: calendarios contra el mayor 2150",
                (int) num(r.get("accounts")), (int) num(r.get("off")), dec(r.get("diff")), dec(r.get("sub")),
                dec(r.get("gl")), (String) r.get("sample"), "calendarios");
    }

    /** 1205, account by account, against what the origination costs' schedules still defer. */
    private CloseCheck originationCostsVsLedger() {
        Map<String, Object> r = jdbc.queryForMap("""
                WITH gl AS (
                    SELECT credit_account_id AS account,
                           SUM(CASE WHEN debit_account = '1205' THEN amount ELSE 0 END)
                         - SUM(CASE WHEN credit_account = '1205' THEN amount ELSE 0 END) AS pending
                      FROM accounting.journal_entries
                     WHERE debit_account = '1205' OR credit_account = '1205'
                     GROUP BY credit_account_id),
                schedules AS (
                    SELECT credit_account_id AS account, SUM(total - recognized) AS pending
                      FROM accounting.deferred_fees WHERE kind = 'COST' GROUP BY credit_account_id),
                both_sides AS (
                    SELECT COALESCE(sc.account, gl.account) AS account,
                           COALESCE(sc.pending, 0) AS sub, COALESCE(gl.pending, 0) AS gl
                      FROM schedules sc FULL OUTER JOIN gl ON gl.account = sc.account)
                SELECT count(*) AS accounts, COALESCE(SUM(sub), 0) AS sub, COALESCE(SUM(gl), 0) AS gl,
                       count(*) FILTER (WHERE abs(sub - gl) > 0.005) AS off,
                       COALESCE(SUM(abs(sub - gl)) FILTER (WHERE abs(sub - gl) > 0.005), 0) AS diff,
                       array_to_string((array_agg(COALESCE(account::text, 'sin crédito') ORDER BY abs(sub - gl) DESC)
                                        FILTER (WHERE abs(sub - gl) > 0.005))[1 : 5], ', ') AS sample
                  FROM both_sides""", new MapSqlParameterSource());
        return compare("COSTOS_1205", "Costos de originación por amortizar: calendarios contra el mayor 1205",
                (int) num(r.get("accounts")), (int) num(r.get("off")), dec(r.get("diff")), dec(r.get("sub")),
                dec(r.get("gl")), (String) r.get("sample"), "calendarios");
    }

    /**
     * Every opening fee recognized the months elapsed up to this one (IFRS 9 effective interest). The close books the
     * month's share before reconciling; a preview reports what it will book.
     */
    private CloseCheck feesAmortized(MapSqlParameterSource p, boolean amortized) {
        Map<String, Object> r = jdbc.queryForMap("""
                SELECT count(*) AS behind, COALESCE(SUM(target - recognized), 0) AS amount
                  FROM (SELECT f.recognized,
                               LEAST(f.total, ROUND(f.total * LEAST(f.months, f.elapsed) / f.months, 2)) AS target
                          FROM (SELECT d.*, ((CAST(substr(:period, 1, 4) AS int) - CAST(substr(d.first_period, 1, 4) AS int)) * 12
                                             + CAST(substr(:period, 5, 2) AS int) - CAST(substr(d.first_period, 5, 2) AS int) + 1) AS elapsed
                                  FROM accounting.deferred_fees d
                                 WHERE d.status = 'ACTIVE' AND d.first_period <= :period) f) x
                 WHERE target - recognized > 0.005""", p);
        int behind = (int) num(r.get("behind"));
        BigDecimal amount = dec(r.get("amount"));
        if (!amortized) {
            return new CloseCheck("DEVENGO_COMISIONES", "Devengo del mes de comisiones y costos de originación (tasa efectiva)", true, false,
                    behind, amount, behind == 0 ? "nada por devengar en el mes"
                            : "al cerrar se devengan " + money(amount) + " de " + behind + " comisiones o costos");
        }
        return new CloseCheck("DEVENGO_COMISIONES", "Devengo del mes de comisiones y costos de originación (tasa efectiva)", behind == 0, true,
                behind, amount, behind == 0 ? "todas las comisiones y costos devengados hasta el mes"
                        : behind + " comisiones o costos con " + money(amount) + " sin devengar");
    }

    /** The order accounts move in pairs: authorized lines, written-off portfolio, Stage 3 interest not recognized. */
    private CloseCheck orderAccounts() {
        Map<String, BigDecimal> b = new HashMap<>();
        jdbc.query("""
                SELECT code, SUM(d) - SUM(c) AS balance FROM (
                    SELECT debit_account AS code, amount AS d, 0::numeric AS c FROM accounting.journal_entries
                     WHERE debit_account IN ('7101', '7201', '1210', '7301', '7401', '7402')
                    UNION ALL
                    SELECT credit_account, 0::numeric, amount FROM accounting.journal_entries
                     WHERE credit_account IN ('7101', '7201', '1210', '7301', '7401', '7402')) m
                 GROUP BY code""", new MapSqlParameterSource(),
                (ResultSet rs) -> { b.put(rs.getString("code"), rs.getBigDecimal("balance")); });
        String[][] pairs = {{"7101", "7201"}, {"1210", "7301"}, {"7401", "7402"}};
        List<String> off = new ArrayList<>();
        for (String[] pair : pairs) {
            BigDecimal sum = b.getOrDefault(pair[0], BigDecimal.ZERO).add(b.getOrDefault(pair[1], BigDecimal.ZERO));
            if (sum.abs().compareTo(TOLERANCE) > 0) off.add(pair[0] + "/" + pair[1] + " " + money(sum));
        }
        return new CloseCheck("CUENTAS_DE_ORDEN", "Cuentas de orden en pares", off.isEmpty(), true, off.size(), null,
                off.isEmpty() ? "líneas 7101/7201, castigada 1210/7301 e interés de etapa 3 7401/7402 cuadran entre sí"
                              : "no cuadran: " + String.join(", ", off));
    }

    /** No account incorporated against equity: every account is born in the platform. */
    private CloseCheck noIncorporations() {
        Map<String, Object> r = jdbc.queryForMap("""
                SELECT count(*) AS entries, COALESCE(SUM(amount), 0) AS amount FROM accounting.journal_entries
                 WHERE trigger_event = 'OPENING_BALANCE' OR debit_account = '3901' OR credit_account = '3901'""",
                new MapSqlParameterSource());
        int n = (int) num(r.get("entries"));
        return new CloseCheck("SIN_INCORPORACIONES", "Sin incorporaciones contra capital (3901)", n == 0, true, n,
                dec(r.get("amount")), n == 0 ? "ningún crédito incorporado" : n + " asientos por " + money(dec(r.get("amount"))));
    }

    /** Money moves in centavos: an entry with a fraction of a centavo cannot be settled by any payment. */
    private CloseCheck centavos(MapSqlParameterSource p) {
        Map<String, Object> r = jdbc.queryForMap("""
                SELECT count(*) AS entries, COALESCE(string_agg(DISTINCT trigger_event, ', '), '') AS facts
                  FROM accounting.journal_entries WHERE period = :period AND amount <> round(amount, 2)""", p);
        int n = (int) num(r.get("entries"));
        return new CloseCheck("CENTAVOS", "Asientos en centavos", n == 0, true, n, null,
                n == 0 ? "todos los asientos del mes en centavos" : n + " asientos con fracción de centavo: " + r.get("facts"));
    }

    /** Facts of an earlier, already closed month booked in this one: reported, they do not keep the month open. */
    private CloseCheck latePostings(MapSqlParameterSource p) {
        Map<String, Object> r = jdbc.queryForMap("""
                SELECT count(*) AS vouchers, COALESCE(SUM(total_debit), 0) AS amount,
                       COALESCE(string_agg(DISTINCT original_period, ', '), '') AS months
                  FROM accounting.vouchers WHERE period = :period AND is_late_posting""", p);
        int n = (int) num(r.get("vouchers"));
        return new CloseCheck("EXTEMPORANEAS", "Pólizas extemporáneas recibidas en el mes", n == 0, false, n,
                dec(r.get("amount")), n == 0 ? "ninguna"
                        : n + " pólizas por " + money(dec(r.get("amount"))) + " de " + r.get("months"));
    }

    /**
     * 1101 against treasury's cash book, by business day. Treasury keeps every movement of the account from its own
     * facts (the STP orders settled, the funding, the clients' payments, the returns, the commissions paid); the
     * ledger books 1101 from the portfolio's and the other services' facts. SPEI money (credits received on the
     * collection CLABEs, orders reported out) reaches 1101 only through the cash book's bank side, on the operation
     * date, against the transit account the portfolio's side used: the transit checks reconcile that side. The month closes only if both records say
     * the same, kind by kind (disbursements, payments, returns, funding, commissions) and in the balance at its end. A
     * payment the portfolio rejected came in and went back: it never reached the books, so the pair is left out.
     */
    private CloseCheck treasuryVsLedger(MapSqlParameterSource p) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                WITH rejected AS (
                    SELECT substr(source_event_id, length('PAYMENT-REJECTED:') + 1) AS payment
                      FROM accounting.treasury_cash_book WHERE source_event_id LIKE 'PAYMENT-REJECTED:%'),
                book AS (
                    SELECT CASE movement_type WHEN 'STP_ORDER' THEN 'disposiciones' WHEN 'CLIENT_PAYMENT' THEN 'pagos'
                                WHEN 'PAYMENT_RETURN' THEN 'devoluciones' WHEN 'FUNDING' THEN 'fondeo'
                                WHEN 'COMMISSION_PAYOUT' THEN 'comisiones' ELSE lower(movement_type) END AS kind,
                           period, CASE direction WHEN 'CREDIT' THEN amount ELSE -amount END AS amount
                      FROM accounting.treasury_cash_book b
                     WHERE period <= :period
                       AND source_event_id NOT LIKE 'PAYMENT-REJECTED:%'
                       AND NOT (movement_type = 'CLIENT_PAYMENT'
                                AND substr(source_event_id, length('PAYMENT:') + 1) IN (SELECT payment FROM rejected))),
                ledger AS (
                    SELECT CASE WHEN e.trigger_event LIKE 'DISPOSITION%' OR e.trigger_event = 'TREASURY_STP_ORDER'
                                     THEN 'disposiciones'
                                WHEN e.trigger_event IN ('PAYMENT_APPLIED', 'RECOVERY_PAYMENT', 'TREASURY_SPEI_CREDIT')
                                     THEN 'pagos'
                                WHEN e.trigger_event IN ('PAYMENT_RETURNED', 'TREASURY_SPEI_CREDIT_RETURNED')
                                     THEN 'devoluciones'
                                WHEN e.trigger_event LIKE 'FUNDING%' THEN 'fondeo'
                                WHEN e.trigger_event LIKE 'COMMISSION%' THEN 'comisiones'
                                ELSE lower(e.trigger_event) END AS kind,
                           e.period, CASE WHEN e.debit_account = '1101' THEN e.amount ELSE -e.amount END AS amount
                      FROM accounting.journal_entries e
                     WHERE (e.debit_account = '1101' OR e.credit_account = '1101') AND e.period <= :period),
                kinds AS (SELECT kind FROM book UNION SELECT kind FROM ledger)
                SELECT k.kind,
                       COALESCE((SELECT SUM(amount) FROM book b WHERE b.kind = k.kind AND b.period = :period), 0) AS book_month,
                       COALESCE((SELECT SUM(amount) FROM ledger l WHERE l.kind = k.kind AND l.period = :period), 0) AS ledger_month,
                       COALESCE((SELECT SUM(amount) FROM book b WHERE b.kind = k.kind), 0) AS book_total,
                       COALESCE((SELECT SUM(amount) FROM ledger l WHERE l.kind = k.kind), 0) AS ledger_total,
                       (SELECT count(*) FROM rejected) AS rejected
                  FROM kinds k ORDER BY k.kind""", p);
        BigDecimal bookBalance = BigDecimal.ZERO, ledgerBalance = BigDecimal.ZERO, worst = BigDecimal.ZERO;
        long rejected = 0;
        List<String> off = new ArrayList<>(), month = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            String kind = (String) r.get("kind");
            BigDecimal bm = dec(r.get("book_month")), lm = dec(r.get("ledger_month"));
            BigDecimal bt = dec(r.get("book_total")), lt = dec(r.get("ledger_total"));
            bookBalance = bookBalance.add(bt);
            ledgerBalance = ledgerBalance.add(lt);
            rejected = num(r.get("rejected"));
            BigDecimal gap = bm.subtract(lm).abs().max(bt.subtract(lt).abs());
            if (gap.compareTo(TOLERANCE) > 0) {
                off.add(kind + ": tesorería " + money(bm) + ", mayor " + money(lm) + " en el mes");
                worst = worst.max(gap);
            } else if (lm.signum() != 0) {
                month.add(kind + " " + money(lm));
            }
        }
        boolean ok = off.isEmpty() && bookBalance.subtract(ledgerBalance).abs().compareTo(TOLERANCE) <= 0;
        String rejections = rejected == 0 ? "" : "; " + rejected + " pagos rechazados entraron y se devolvieron";
        String detail = ok
                ? "saldo " + money(ledgerBalance) + " = libro de caja de tesorería"
                  + (month.isEmpty() ? "" : "; en el mes: " + String.join(", ", month)) + rejections
                : "no concilia con el libro de caja (saldo mayor " + money(ledgerBalance) + ", tesorería "
                  + money(bookBalance) + ")" + (off.isEmpty() ? "" : ": " + String.join("; ", off)) + rejections;
        return new CloseCheck("BANCOS_1101", "Bancos 1101 contra el libro de caja de tesorería", ok, true,
                ok ? 0 : Math.max(1, off.size()), ok ? BigDecimal.ZERO : worst.max(bookBalance.subtract(ledgerBalance).abs()),
                detail);
    }

    private static CloseCheck compare(String code, String title, int accounts, int off, BigDecimal diff,
                                      BigDecimal subledger, BigDecimal ledger, String sample, String source) {
        BigDecimal totalGap = subledger.subtract(ledger).abs();
        boolean ok = off == 0 && totalGap.compareTo(TOLERANCE) <= 0;
        String detail = ok
                ? accounts + " créditos; " + source + " " + money(subledger) + " = mayor " + money(ledger)
                : off + " de " + accounts + " créditos no concilian por " + money(diff) + "; " + source + " "
                  + money(subledger) + ", mayor " + money(ledger) + (sample == null || sample.isBlank() ? "" : ": " + sample);
        return new CloseCheck(code, title, ok, true, off, ok ? BigDecimal.ZERO : diff.max(totalGap), detail);
    }

    private static String money(BigDecimal v) {
        NumberFormat f = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("es-MX"));
        return f.format(v == null ? BigDecimal.ZERO : v);
    }

    private static long num(Object o) {
        return o == null ? 0 : ((Number) o).longValue();
    }

    private static BigDecimal dec(Object o) {
        if (o == null) return BigDecimal.ZERO;
        if (o instanceof BigDecimal b) return b;
        return new BigDecimal(o.toString());
    }
}

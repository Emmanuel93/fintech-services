package com.fintech.accounting.infrastructure.adapter.out.persistence;

import com.fintech.accounting.application.CloseReport.CloseCheck;
import com.fintech.accounting.application.AccountingProperties;
import com.fintech.accounting.domain.AccountCodes;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static com.fintech.accounting.infrastructure.adapter.out.persistence.CheckFormat.dec;
import static com.fintech.accounting.infrastructure.adapter.out.persistence.CheckFormat.money;
import static com.fintech.accounting.infrastructure.adapter.out.persistence.CheckFormat.num;
import static com.fintech.accounting.infrastructure.adapter.out.persistence.CheckFormat.span;

/**
 * The month-end checks of the accounts money crosses between the portfolio's date and the bank's: collections in
 * transit (1103), SPEI to settle (2103) and the payers' balances in favor (2102).
 *
 * <p>A clearing account is reconciled item by item, not as a figure: its balance at a month end must be the list of
 * the items open at that date, each with both of its dates. An item is open while one side is booked and the other
 * is not — a payment received at 19:00 on 30 November was applied by the portfolio on the 30th and is credited by the
 * bank on 1 December, so on the 30th it is open, and that is right. What is not right is an item the bank already
 * reported by the month end and the portfolio booked for another amount, an item booked against no item, or one side
 * the other never sees; any of those blocks the close.
 */
@Component
class ClearingAccountChecks {

    private static final int SAMPLE = 3;

    private final NamedParameterJdbcTemplate jdbc;
    private final AccountingProperties properties;

    ClearingAccountChecks(NamedParameterJdbcTemplate jdbc, AccountingProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    /**
     * 1103 — collections in transit. Its items are the SPEI credits received on the collection CLABEs: the portfolio
     * books each share of a credit (a loan's payment, a recovery, the rest left in favor of the payer) on the day it
     * received it, and treasury's cash book clears the credit on the SPEI operation date.
     */
    CloseCheck collectionsInTransit(String period) {
        Map<String, Object> r = jdbc.queryForMap("""
                WITH lines AS (
                    SELECT v.clearing_ref AS item,
                           e.trigger_event LIKE 'TREASURY_%' AS bank,
                           e.period,
                           CASE WHEN e.debit_account = :account THEN e.amount ELSE -e.amount END AS amount,
                           (e.posting_date AT TIME ZONE :zone)::date AS day
                      FROM accounting.journal_entries e
                      LEFT JOIN accounting.vouchers v ON v.voucher_id = e.voucher_id
                     WHERE :account IN (e.debit_account, e.credit_account)),
                items AS (
                    SELECT item,
                           COALESCE(SUM(amount) FILTER (WHERE period <= :period), 0)            AS open_amount,
                           COALESCE(SUM(amount) FILTER (WHERE period <= :period AND NOT bank), 0) AS applied,
                           COALESCE(-SUM(amount) FILTER (WHERE period <= :period AND bank), 0)   AS credited,
                           COALESCE(bool_or(bank), false)                                        AS reported,
                           COALESCE(bool_or(bank AND period <= :period), false)                  AS reported_by_end,
                           MIN(day) FILTER (WHERE bank)                                          AS bank_day
                      FROM lines GROUP BY item),
                classified AS (
                    SELECT *, CASE WHEN open_amount = 0 THEN 'CLOSED'
                                   WHEN item IS NULL THEN 'UNASSIGNED'
                                   WHEN reported_by_end THEN 'UNMATCHED'
                                   WHEN NOT reported THEN 'UNREPORTED'
                                   ELSE 'IN_TRANSIT' END AS state
                      FROM items)
                SELECT COALESCE(SUM(open_amount), 0)                                                 AS balance,
                       COUNT(*) FILTER (WHERE state = 'IN_TRANSIT')                                   AS in_transit,
                       COALESCE(SUM(open_amount) FILTER (WHERE state = 'IN_TRANSIT'), 0)              AS in_transit_amount,
                       MIN(bank_day) FILTER (WHERE state = 'IN_TRANSIT')                              AS first_day,
                       MAX(bank_day) FILTER (WHERE state = 'IN_TRANSIT')                              AS last_day,
                       COUNT(*) FILTER (WHERE state IN ('UNASSIGNED', 'UNMATCHED', 'UNREPORTED'))     AS off,
                       COALESCE(MAX(abs(open_amount)) FILTER (WHERE state IN ('UNASSIGNED', 'UNMATCHED', 'UNREPORTED')), 0) AS worst,
                       (SELECT string_agg(sample, '; ') FROM (
                            SELECT CASE state
                                     WHEN 'UNASSIGNED' THEN 'movimientos sin abono por ' || to_char(open_amount, 'FM999,999,990.00')
                                     WHEN 'UNMATCHED' THEN 'abono ' || left(item, 8) || ': cartera '
                                          || to_char(applied, 'FM999,999,990.00') || ', banco ' || to_char(credited, 'FM999,999,990.00')
                                     ELSE 'abono ' || left(item, 8) || ' aplicado por '
                                          || to_char(applied, 'FM999,999,990.00') || ' que el banco no reporta' END AS sample
                              FROM classified WHERE state IN ('UNASSIGNED', 'UNMATCHED', 'UNREPORTED')
                             ORDER BY abs(open_amount) DESC, item LIMIT :sample) s)                    AS sample
                  FROM classified""",
                params(period, AccountCodes.REMESAS_EN_CAMINO));
        long off = num(r.get("off"));
        long inTransit = num(r.get("in_transit"));
        BigDecimal balance = dec(r.get("balance"));
        BigDecimal transitAmount = dec(r.get("in_transit_amount"));
        String transit = inTransit == 0 ? "sin abonos en tránsito"
                : inTransit + " abonos por " + money(transitAmount) + " en tránsito: el banco los acredita "
                  + span(r.get("first_day"), r.get("last_day"));
        String detail = off == 0
                ? "saldo " + money(balance) + " = " + transit
                : off + " partidas no se explican (" + r.get("sample") + "); saldo " + money(balance) + ", " + transit;
        return new CloseCheck("TRANSITO_COBRANZA_1103", "Cobranza SPEI en tránsito (1103): saldo = abonos por acreditar",
                off == 0, true, (int) (off == 0 ? inTransit : off), off == 0 ? transitAmount : dec(r.get("worst")), detail);
    }

    /**
     * 2103 — SPEI to settle. Its items are the loans whose disposition went out by SPEI: the portfolio credits it on the
     * disposition's day (and debits it if the disposition failed or came back before settling), treasury's cash book
     * clears it on the order's operation date. The bank's report names the order, not the loan: disbursement's
     * acceptance of the order says which loan it paid out. A lot of commissions is an item too ({@code LOTE:<id>}):
     * its SPEI requested credits the net, a failed one debits it back, and the bank's report of its order clears it.
     */
    CloseCheck payoutsInTransit(String period) {
        Map<String, Object> r = jdbc.queryForMap("""
                WITH lines AS (
                    SELECT CASE WHEN e.trigger_event = 'TREASURY_STP_ORDER'
                                THEN COALESCE(l.credit_account_id::text, l.item_ref, 'ORDEN:' || COALESCE(v.clearing_ref, '?'))
                                ELSE COALESCE(e.credit_account_id::text, v.clearing_ref) END AS item,
                           e.trigger_event = 'TREASURY_STP_ORDER' AS bank,
                           e.period,
                           CASE WHEN e.credit_account = :account THEN e.amount ELSE -e.amount END AS amount,
                           (e.posting_date AT TIME ZONE :zone)::date AS day
                      FROM accounting.journal_entries e
                      LEFT JOIN accounting.vouchers v ON v.voucher_id = e.voucher_id
                      LEFT JOIN accounting.payout_links l
                             ON e.trigger_event = 'TREASURY_STP_ORDER' AND l.payment_request_id::text = v.clearing_ref
                     WHERE :account IN (e.debit_account, e.credit_account)),
                items AS (
                    SELECT item,
                           COALESCE(SUM(amount) FILTER (WHERE period <= :period), 0)             AS open_amount,
                           COALESCE(SUM(amount) FILTER (WHERE period <= :period AND NOT bank), 0) AS disposed,
                           COALESCE(-SUM(amount) FILTER (WHERE period <= :period AND bank), 0)   AS settled,
                           MIN(day) FILTER (WHERE bank AND period > :period)                     AS settled_after
                      FROM lines GROUP BY item),
                classified AS (
                    SELECT *, CASE WHEN open_amount = 0 THEN 'CLOSED'
                                   WHEN item IS NULL OR item LIKE 'ORDEN:%' THEN 'UNASSIGNED'
                                   WHEN open_amount < 0 THEN 'OVERPAID'
                                   ELSE 'IN_TRANSIT' END AS state
                      FROM items)
                SELECT COALESCE(SUM(open_amount), 0)                                          AS balance,
                       COUNT(*) FILTER (WHERE state = 'IN_TRANSIT')                            AS in_transit,
                       COALESCE(SUM(open_amount) FILTER (WHERE state = 'IN_TRANSIT'), 0)       AS in_transit_amount,
                       COUNT(*) FILTER (WHERE state = 'IN_TRANSIT' AND settled_after IS NOT NULL) AS settled_later,
                       MIN(settled_after) FILTER (WHERE state = 'IN_TRANSIT')                  AS first_day,
                       MAX(settled_after) FILTER (WHERE state = 'IN_TRANSIT')                  AS last_day,
                       COUNT(*) FILTER (WHERE state IN ('UNASSIGNED', 'OVERPAID'))              AS off,
                       COALESCE(MAX(abs(open_amount)) FILTER (WHERE state IN ('UNASSIGNED', 'OVERPAID')), 0) AS worst,
                       (SELECT string_agg(sample, '; ') FROM (
                            SELECT CASE WHEN state = 'UNASSIGNED' THEN 'orden ' || substr(COALESCE(item, '?'), 7, 8)
                                          || ' sin crédito por ' || to_char(abs(open_amount), 'FM999,999,990.00')
                                     WHEN item LIKE 'LOTE:%' THEN 'lote ' || substr(item, 6, 8) || ': ordenado '
                                          || to_char(disposed, 'FM999,999,990.00') || ', liquidado '
                                          || to_char(settled, 'FM999,999,990.00')
                                     ELSE 'crédito ' || left(item, 8) || ': dispuesto '
                                          || to_char(disposed, 'FM999,999,990.00') || ', liquidado '
                                          || to_char(settled, 'FM999,999,990.00') END AS sample
                              FROM classified WHERE state IN ('UNASSIGNED', 'OVERPAID')
                             ORDER BY abs(open_amount) DESC, item LIMIT :sample) s)             AS sample
                  FROM classified""",
                params(period, AccountCodes.SPEI_POR_LIQUIDAR));
        long off = num(r.get("off"));
        long inTransit = num(r.get("in_transit"));
        long settledLater = num(r.get("settled_later"));
        BigDecimal balance = dec(r.get("balance"));
        BigDecimal transitAmount = dec(r.get("in_transit_amount"));
        String transit = inTransit == 0 ? "sin dispersiones por liquidar"
                : inTransit + " dispersiones por " + money(transitAmount) + " por liquidar"
                  + (settledLater == 0 ? "" : "; " + settledLater + " las liquida el banco "
                     + span(r.get("first_day"), r.get("last_day")))
                  + (inTransit > settledLater ? "; " + (inTransit - settledLater) + " siguen en vuelo" : "");
        String detail = off == 0
                ? "saldo " + money(balance) + " = " + transit
                : off + " partidas no se explican (" + r.get("sample") + "); saldo " + money(balance) + ", " + transit;
        return new CloseCheck("TRANSITO_DISPERSION_2103", "SPEI por liquidar (2103): saldo = dispersiones sin liquidar",
                off == 0, true, (int) (off == 0 ? inTransit : off), off == 0 ? transitAmount : dec(r.get("worst")), detail);
    }

    /**
     * 2102 — the payers' balances in favor, against payments' own record of each movement of them. The ledger takes
     * the credits from the received credits' leftovers and the debits from the portfolio's payments funded by the
     * balance: two services' facts that must say the same. No payer can owe their own balance.
     */
    CloseCheck balancesInFavor(String period) {
        Map<String, Object> r = jdbc.queryForMap("""
                WITH holders AS (
                    SELECT party_id, SUM(amount) AS balance
                      FROM accounting.customer_balance_movements WHERE period <= :period GROUP BY party_id)
                SELECT (SELECT COALESCE(SUM(CASE WHEN e.credit_account = :account THEN e.amount ELSE -e.amount END), 0)
                          FROM accounting.journal_entries e
                         WHERE :account IN (e.debit_account, e.credit_account) AND e.period <= :period) AS ledger,
                       (SELECT COALESCE(SUM(balance), 0) FROM holders)                AS subledger,
                       (SELECT count(*) FROM holders WHERE balance > 0)               AS holders,
                       (SELECT count(*) FROM holders WHERE balance < 0)               AS negative""",
                params(period, AccountCodes.SALDOS_A_FAVOR));
        BigDecimal ledger = dec(r.get("ledger"));
        BigDecimal subledger = dec(r.get("subledger"));
        long holders = num(r.get("holders"));
        long negative = num(r.get("negative"));
        BigDecimal gap = ledger.subtract(subledger).abs();
        boolean ok = gap.signum() == 0 && negative == 0;
        String detail = ok
                ? holders + " clientes con saldo a favor; movimientos " + money(subledger) + " = mayor " + money(ledger)
                : "mayor " + money(ledger) + ", movimientos de pagos " + money(subledger)
                  + (negative == 0 ? "" : "; " + negative + " clientes con saldo a favor negativo");
        return new CloseCheck("SALDOS_A_FAVOR_2102", "Saldos a favor de clientes (2102) contra sus movimientos",
                ok, true, (int) (ok ? holders : Math.max(1, negative)), ok ? subledger : gap, detail);
    }

    private MapSqlParameterSource params(String period, String account) {
        return new MapSqlParameterSource("period", period).addValue("account", account)
                .addValue("zone", properties.zoneId().getId()).addValue("sample", SAMPLE);
    }

    /** The checks, in the order the close shows them. */
    List<CloseCheck> all(String period) {
        return List.of(collectionsInTransit(period), payoutsInTransit(period), balancesInFavor(period));
    }
}

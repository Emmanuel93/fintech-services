package com.fintech.accounting.infrastructure.adapter.out.persistence;

import com.fintech.accounting.application.CloseReport.CloseCheck;
import com.fintech.accounting.domain.AccountCodes;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;

import static com.fintech.accounting.infrastructure.adapter.out.persistence.CheckFormat.dec;
import static com.fintech.accounting.infrastructure.adapter.out.persistence.CheckFormat.money;
import static com.fintech.accounting.infrastructure.adapter.out.persistence.CheckFormat.num;

/**
 * 2120 — commissions payable, item by item: what is owed to each beneficiary for each product.
 *
 * <p>Each commission voucher names its item: an accrual or a reversal {@code COM:<beneficiary>:<product>}, a lot's SPEI
 * requested or failed its {@code LOTE:<id>} (commission's facts say whose lot it is). The ledger of each item must be
 * what commission reported: its accruals' gross (commission and IVA), less its reversals, less the lots whose SPEI was
 * requested, plus the ones whose SPEI failed. A movement of 2120 no item explains, or an item that does not match,
 * blocks the close. A beneficiary owing back a commission already paid (a clawback larger than what accrued since) is
 * said, not blocked: the next accruals settle it.
 */
@Component
class CommissionPayableChecks {

    private static final int SAMPLE = 3;

    private final NamedParameterJdbcTemplate jdbc;

    CommissionPayableChecks(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    CloseCheck commissionsPayable(String period) {
        Map<String, Object> r = jdbc.queryForMap("""
                WITH lots AS (
                    SELECT DISTINCT ON (batch_id) batch_id, beneficiary_party_id, product_type
                      FROM accounting.commission_facts WHERE batch_id IS NOT NULL
                     ORDER BY batch_id, received_at),
                ledger AS (
                    SELECT CASE WHEN v.clearing_ref LIKE 'COM:%' THEN substr(v.clearing_ref, 5)
                                WHEN v.clearing_ref LIKE 'LOTE:%'
                                THEN lt.beneficiary_party_id::text || ':' || coalesce(lt.product_type, '') END AS item,
                           CASE WHEN e.credit_account = :account THEN e.amount ELSE -e.amount END AS amount
                      FROM accounting.journal_entries e
                      LEFT JOIN accounting.vouchers v ON v.voucher_id = e.voucher_id
                      LEFT JOIN lots lt ON v.clearing_ref LIKE 'LOTE:%' AND lt.batch_id::text = substr(v.clearing_ref, 6)
                     WHERE :account IN (e.debit_account, e.credit_account) AND e.period <= :period),
                facts AS (
                    SELECT beneficiary_party_id::text || ':' || coalesce(product_type, '') AS item, gross AS amount
                      FROM accounting.commission_facts WHERE period <= :period),
                items AS (
                    SELECT item, sum(ledger) AS ledger, sum(facts) AS facts FROM (
                        SELECT item, amount AS ledger, 0 AS facts FROM ledger
                        UNION ALL
                        SELECT item, 0, amount FROM facts) x
                     GROUP BY item)
                SELECT coalesce(sum(ledger), 0)                                                    AS balance,
                       coalesce(sum(facts) FILTER (WHERE item IS NOT NULL), 0)                      AS owed,
                       count(*) FILTER (WHERE item IS NOT NULL AND facts > 0)                       AS owing,
                       count(*) FILTER (WHERE item IS NOT NULL AND facts < 0)                       AS owed_back,
                       coalesce(-sum(facts) FILTER (WHERE item IS NOT NULL AND facts < 0), 0)       AS owed_back_amount,
                       coalesce(sum(ledger) FILTER (WHERE item IS NULL), 0)                         AS unattributed,
                       count(*) FILTER (WHERE item IS NOT NULL AND ledger <> facts)                 AS off,
                       coalesce(max(abs(ledger - facts)) FILTER (WHERE item IS NOT NULL), 0)        AS worst,
                       (SELECT string_agg(sample, '; ') FROM (
                            SELECT 'beneficiaria ' || left(item, 8) || ' · '
                                   || coalesce(nullif(split_part(item, ':', 2), ''), 'sin producto')
                                   || ': comisiones ' || to_char(facts, 'FM999,999,990.00')
                                   || ', mayor ' || to_char(ledger, 'FM999,999,990.00') AS sample
                              FROM items WHERE item IS NOT NULL AND ledger <> facts
                             ORDER BY abs(ledger - facts) DESC, item LIMIT :sample) s)                AS sample
                  FROM items""",
                new MapSqlParameterSource("period", period).addValue("account", AccountCodes.COMISIONES_POR_PAGAR)
                        .addValue("sample", SAMPLE));
        BigDecimal balance = dec(r.get("balance"));
        BigDecimal owed = dec(r.get("owed"));
        BigDecimal unattributed = dec(r.get("unattributed"));
        long off = num(r.get("off"));
        long owing = num(r.get("owing"));
        long owedBack = num(r.get("owed_back"));
        boolean ok = off == 0 && unattributed.signum() == 0;
        String back = owedBack == 0 ? "" : "; " + owedBack + " beneficiarias deben " + money(dec(r.get("owed_back_amount")))
                + " de comisiones ya pagadas (las descuenta su siguiente lote)";
        String detail;
        if (ok) {
            detail = owing == 0 && owedBack == 0 ? "sin comisiones por pagar"
                    : owing + " beneficiarias·producto con " + money(owed.add(dec(r.get("owed_back_amount"))))
                      + " por pagar; comisiones = mayor " + money(balance) + back;
        } else {
            detail = (off == 0 ? "" : off + " beneficiarias·producto no cuadran (" + r.get("sample") + ")")
                    + (unattributed.signum() == 0 ? "" : (off == 0 ? "" : "; ") + money(unattributed)
                       + " en 2120 sin beneficiaria")
                    + "; comisiones " + money(owed) + ", mayor " + money(balance);
        }
        return new CloseCheck("COMISIONES_2120", "Comisiones por pagar (2120) contra lo que commission reportó",
                ok, true, (int) (ok ? owing : Math.max(1, off)), ok ? owed : dec(r.get("worst")).max(unattributed.abs()),
                detail);
    }
}

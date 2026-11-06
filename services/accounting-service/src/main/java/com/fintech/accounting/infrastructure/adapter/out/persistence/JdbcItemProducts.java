package com.fintech.accounting.infrastructure.adapter.out.persistence;

import com.fintech.accounting.application.port.out.ItemProducts;
import com.fintech.accounting.domain.BankSideRule;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The product of a clearing item, from accounting's own records: an order's through its payout link (the loan's
 * product, or the lot's as commission reported it), a received credit's through the vouchers the portfolio booked
 * when it applied it.
 */
@Repository
class JdbcItemProducts implements ItemProducts {

    /** The product of each order: its loan's, or its commission lot's. Joined by {@code l.payment_request_id}. */
    private static final String ORDER_PRODUCT = """
            SELECT l.payment_request_id, coalesce(s.product_type, f.product_type) AS product_type
              FROM accounting.payout_links l
              LEFT JOIN accounting.account_balance_shadows s ON s.credit_account_id = l.credit_account_id
              LEFT JOIN LATERAL (SELECT c.product_type FROM accounting.commission_facts c
                                  WHERE l.item_ref LIKE 'LOTE:%' AND c.batch_id::text = substr(l.item_ref, 6)
                                    AND c.product_type IS NOT NULL
                                  ORDER BY c.received_at LIMIT 1) f ON true""";

    private final NamedParameterJdbcTemplate jdbc;

    JdbcItemProducts(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<String> ofOrder(String paymentRequestId) {
        UUID order = uuidOrNull(paymentRequestId);
        if (order == null) return Optional.empty();
        List<String> found = jdbc.queryForList("SELECT o.product_type FROM (" + ORDER_PRODUCT
                        + ") o WHERE o.payment_request_id = :order AND o.product_type IS NOT NULL",
                Map.of("order", order), String.class);
        return found.stream().findFirst();
    }

    @Override
    public Optional<String> ofReceivedCredit(String creditRef) {
        List<String> found = jdbc.queryForList("""
                SELECT min(product_type) FROM accounting.vouchers
                 WHERE clearing_ref = :ref AND credit_account_id IS NOT NULL AND product_type IS NOT NULL
                HAVING count(DISTINCT product_type) = 1""", Map.of("ref", creditRef), String.class);
        return found.stream().findFirst();
    }

    @Override
    public int sweepOpenPeriods() {
        int loans = jdbc.update("""
                UPDATE accounting.vouchers v SET product_type = s.product_type
                  FROM accounting.account_balance_shadows s, accounting.accounting_periods p
                 WHERE v.product_type IS NULL AND v.credit_account_id = s.credit_account_id
                   AND s.product_type IS NOT NULL AND p.period = v.period AND p.status = 'OPEN'""", Map.of());
        int orders = jdbc.update("""
                WITH pending AS (
                    SELECT v.voucher_id, v.clearing_ref FROM accounting.vouchers v
                      JOIN accounting.accounting_periods p ON p.period = v.period AND p.status = 'OPEN'
                     WHERE v.product_type IS NULL AND v.credit_account_id IS NULL AND v.trigger_event = :order
                       AND v.clearing_ref IS NOT NULL)
                UPDATE accounting.vouchers v SET product_type = o.product_type
                  FROM pending x
                  JOIN (""" + ORDER_PRODUCT + """
                       ) o ON o.payment_request_id::text = x.clearing_ref
                 WHERE v.voucher_id = x.voucher_id AND o.product_type IS NOT NULL""",
                Map.of("order", BankSideRule.STP_ORDER.trigger()));
        int credits = jdbc.update("""
                WITH pending AS (
                    SELECT v.voucher_id, v.clearing_ref FROM accounting.vouchers v
                      JOIN accounting.accounting_periods p ON p.period = v.period AND p.status = 'OPEN'
                     WHERE v.product_type IS NULL AND v.credit_account_id IS NULL AND v.trigger_event IN (:credits)
                       AND v.clearing_ref IS NOT NULL),
                known AS (
                    SELECT a.clearing_ref, min(a.product_type) AS product_type
                      FROM accounting.vouchers a
                     WHERE a.clearing_ref IN (SELECT clearing_ref FROM pending)
                       AND a.credit_account_id IS NOT NULL AND a.product_type IS NOT NULL
                     GROUP BY a.clearing_ref HAVING count(DISTINCT a.product_type) = 1)
                UPDATE accounting.vouchers v SET product_type = k.product_type
                  FROM pending x JOIN known k ON k.clearing_ref = x.clearing_ref
                 WHERE v.voucher_id = x.voucher_id""",
                new MapSqlParameterSource("credits", List.of(BankSideRule.SPEI_CREDIT.trigger(),
                        BankSideRule.SPEI_CREDIT_RETURNED.trigger())));
        jdbc.update("""
                UPDATE accounting.journal_entries e SET product_type = v.product_type
                  FROM accounting.vouchers v, accounting.accounting_periods p
                 WHERE e.voucher_id = v.voucher_id AND e.product_type IS NULL AND v.product_type IS NOT NULL
                   AND p.period = e.period AND p.status = 'OPEN'""", Map.of());
        return loans + orders + credits;
    }

    private static UUID uuidOrNull(String text) {
        if (text == null) return null;
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException notAnOrder) {
            return null;
        }
    }
}

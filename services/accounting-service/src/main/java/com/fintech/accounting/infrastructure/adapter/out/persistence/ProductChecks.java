package com.fintech.accounting.infrastructure.adapter.out.persistence;

import com.fintech.accounting.application.CloseReport.CloseCheck;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.fintech.accounting.infrastructure.adapter.out.persistence.CheckFormat.dec;
import static com.fintech.accounting.infrastructure.adapter.out.persistence.CheckFormat.money;

/**
 * The close's checks by product: the portfolio's principal (1201), what it has to collect (1203) and the reserve
 * (1290), product by product — the ledger by the product stamped on its entries against the subledgers by the loan's
 * product. Where the account-by-account checks say the ledger equals the portfolio, these say it is attributed to the
 * right product: an entry of a loan booked under another product, or under none, shows here.
 *
 * <p>Informative (they do not block the close) until they are seen stable, as decided for the product dimension.
 */
@Component
class ProductChecks {

    private static final BigDecimal TOLERANCE = new BigDecimal("0.005");

    /** The ledger's 1201 and 1203 by product against the portfolio's principal and receivable by product. */
    private static final String PORTFOLIO = """
            WITH gl AS (
                SELECT product_type,
                       SUM(CASE WHEN debit_account = '1201' THEN amount ELSE 0 END)
                     - SUM(CASE WHEN credit_account = '1201' THEN amount ELSE 0 END) AS principal,
                       SUM(CASE WHEN debit_account = '1203' THEN amount ELSE 0 END)
                     - SUM(CASE WHEN credit_account = '1203' THEN amount ELSE 0 END) AS receivable
                  FROM accounting.journal_entries
                 WHERE debit_account IN ('1201', '1203') OR credit_account IN ('1201', '1203')
                 GROUP BY product_type),
            sub AS (
                SELECT product_type, SUM(principal_balance) AS principal,
                       SUM(total_debt - principal_balance) AS receivable
                  FROM accounting.account_balance_shadows
                 GROUP BY product_type)
            SELECT COALESCE(sub.product_type, gl.product_type) AS product,
                   COALESCE(sub.principal, 0) AS sub_principal, COALESCE(gl.principal, 0) AS gl_principal,
                   COALESCE(sub.receivable, 0) AS sub_receivable, COALESCE(gl.receivable, 0) AS gl_receivable
              FROM sub FULL OUTER JOIN gl ON COALESCE(gl.product_type, '') = COALESCE(sub.product_type, '')
             ORDER BY 1 NULLS FIRST""";

    /** The ledger's 1290 by product against the reserves risk measured, by the loan's product. */
    private static final String RESERVE = """
            WITH gl AS (
                SELECT product_type,
                       SUM(CASE WHEN credit_account = '1290' THEN amount ELSE 0 END)
                     - SUM(CASE WHEN debit_account = '1290' THEN amount ELSE 0 END) AS allowance
                  FROM accounting.journal_entries
                 WHERE debit_account = '1290' OR credit_account = '1290'
                 GROUP BY product_type),
            sub AS (
                SELECT s.product_type, SUM(p.last_booked_provision) AS allowance
                  FROM accounting.provision_ledger p
                  LEFT JOIN accounting.account_balance_shadows s ON s.credit_account_id = p.credit_account_id
                 GROUP BY s.product_type)
            SELECT COALESCE(sub.product_type, gl.product_type) AS product,
                   COALESCE(sub.allowance, 0) AS sub, COALESCE(gl.allowance, 0) AS gl
              FROM sub FULL OUTER JOIN gl ON COALESCE(gl.product_type, '') = COALESCE(sub.product_type, '')
             ORDER BY 1 NULLS FIRST""";

    private final NamedParameterJdbcTemplate jdbc;

    ProductChecks(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<CloseCheck> all() {
        List<Map<String, Object>> portfolio = jdbc.queryForList(PORTFOLIO, new MapSqlParameterSource());
        List<Map<String, Object>> reserve = jdbc.queryForList(RESERVE, new MapSqlParameterSource());
        return List.of(
                compare("CAPITAL_1201_POR_PRODUCTO", "Capital por producto: cartera contra el mayor 1201", portfolio,
                        "sub_principal", "gl_principal", "cartera"),
                compare("POR_COBRAR_1203_POR_PRODUCTO", "Por cobrar por producto: cartera contra el mayor 1203",
                        portfolio, "sub_receivable", "gl_receivable", "cartera"),
                compare("RESERVA_1290_POR_PRODUCTO", "Estimación preventiva por producto: reservas contra el mayor 1290",
                        reserve, "sub", "gl", "reservas"));
    }

    private static CloseCheck compare(String code, String title, List<Map<String, Object>> rows, String subKey,
                                      String glKey, String source) {
        List<String> fine = new ArrayList<>();
        List<String> off = new ArrayList<>();
        BigDecimal gap = BigDecimal.ZERO;
        int products = 0;
        for (Map<String, Object> r : rows) {
            BigDecimal sub = dec(r.get(subKey));
            BigDecimal gl = dec(r.get(glKey));
            if (sub.signum() == 0 && gl.signum() == 0) continue;
            products++;
            String name = nameOf((String) r.get("product"));
            BigDecimal diff = sub.subtract(gl).abs();
            if (diff.compareTo(TOLERANCE) > 0) {
                off.add(name + ": " + source + " " + money(sub) + ", mayor " + money(gl));
                gap = gap.add(diff);
            } else {
                fine.add(name + " " + money(sub));
            }
        }
        boolean ok = off.isEmpty();
        String detail = products == 0 ? "sin saldos"
                : ok ? products + " productos, " + source + " = mayor: " + String.join(", ", fine)
                     : off.size() + " de " + products + " productos no concilian (" + String.join("; ", off) + ")";
        return new CloseCheck(code, title, ok, false, ok ? products : off.size(), ok ? BigDecimal.ZERO : gap, detail);
    }

    private static String nameOf(String product) {
        return product == null ? "Institución (sin producto)" : product;
    }
}

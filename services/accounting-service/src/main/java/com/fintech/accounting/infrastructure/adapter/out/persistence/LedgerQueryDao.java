package com.fintech.accounting.infrastructure.adapter.out.persistence;

import com.fintech.accounting.application.LedgerViews.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Las consultas del mayor, en SQL.
 *
 * <p><b>Por qué SQL a mano y no derivadas de Spring Data.</b> Todo lo que esta pantalla pregunta es
 * una agregación con filtro opcional por subárbol: «cargos y abonos de agosto en la región Norte»,
 * «cuánto devengó cada sucursal». Con repositorios derivados eso se resuelve trayendo las filas y
 * sumando en Java, que es exactamente lo que hacía la balanza anterior —{@code findByPeriod} del
 * período completo— y lo que impide filtrar por sucursal sin traerlo todo.
 *
 * <p><b>El filtro de alcance viaja como lista de códigos.</b> El BFF resuelve el subárbol contra
 * sales-org en <b>una</b> llamada y manda los códigos; accounting no habla con sales-org ni guarda
 * una copia del árbol. La alternativa —una ruta materializada aquí— obligaría a mantener el árbol
 * sincronizado en dos servicios para una consulta que ya viene acotada.
 */
@Repository
@Transactional(readOnly = true)
public class LedgerQueryDao {

    /** `null` significa «sin sucursal» y hay que preguntarlo con IS NULL, no con IN. */
    private static final String SIN_SUCURSAL = "SIN_SUCURSAL";

    /** A {@code null} product is the institution's (its funding and its interest): asked with IS NULL as well. */
    public static final String INSTITUCION = "INSTITUCION";

    @PersistenceContext
    private EntityManager em;

    // ── Pólizas ──────────────────────────────────────────────────────────────

    public long countVouchers(String period, List<String> unitCodes, String type,
                              UUID creditAccountId, UUID partyId) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM accounting.vouchers v WHERE 1=1");
        Map<String, Object> params = new LinkedHashMap<>();
        appendVoucherFilters(sql, params, period, unitCodes, type, creditAccountId, partyId);
        Query q = em.createNativeQuery(sql.toString());
        params.forEach(q::setParameter);
        return ((Number) q.getSingleResult()).longValue();
    }

    public List<VoucherView> searchVouchers(String period, List<String> unitCodes, String type,
                                            UUID creditAccountId, UUID partyId, int page, int size) {
        StringBuilder sql = new StringBuilder("""
                SELECT v.voucher_id, v.voucher_type, v.folio, v.period, v.voucher_date, v.org_unit_code,
                       v.concept, v.trigger_event, v.source_event_id, v.credit_account_id, v.obligor_party_id,
                       v.total_debit, v.total_credit, v.status, v.is_late_posting, v.original_period,
                       v.reversal_ref, v.product_type
                  FROM accounting.vouchers v
                 WHERE 1=1""");
        Map<String, Object> params = new LinkedHashMap<>();
        appendVoucherFilters(sql, params, period, unitCodes, type, creditAccountId, partyId);
        sql.append(" ORDER BY v.voucher_date DESC, v.folio DESC LIMIT :limit OFFSET :offset");
        params.put("limit", size);
        params.put("offset", (long) page * size);

        Query q = em.createNativeQuery(sql.toString());
        params.forEach(q::setParameter);
        @SuppressWarnings("unchecked") List<Object[]> rows = q.getResultList();
        return rows.stream().map(r -> voucherOf(r, List.of())).toList();
    }

    /**
     * The bank's side of a loan's money: the order that paid it out (named by the payout link) and the SPEI credits
     * that paid it (named by its payments' clearing items). Resolved when read, so it does not depend on which fact
     * arrived first, and no posted voucher is touched.
     */
    private static final String BANK_SIDE_OF_A_LOAN = """
            SELECT b.voucher_id FROM accounting.vouchers b
             WHERE b.trigger_event LIKE 'TREASURY_%'
               AND b.clearing_ref IN (SELECT l.payment_request_id::text FROM accounting.payout_links l
                                       WHERE l.credit_account_id = :creditAccountId
                                      UNION ALL
                                      SELECT p.clearing_ref FROM accounting.vouchers p
                                       WHERE p.credit_account_id = :creditAccountId AND p.clearing_ref IS NOT NULL)""";

    private void appendVoucherFilters(StringBuilder sql, Map<String, Object> params, String period,
                                      List<String> unitCodes, String type,
                                      UUID creditAccountId, UUID partyId) {
        if (period != null && !period.isBlank()) {
            sql.append(" AND v.period = :period");
            params.put("period", period);
        }
        appendUnitScope(sql, params, unitCodes, "v.org_unit_code");
        if (type != null && !type.isBlank()) {
            sql.append(" AND v.voucher_type = :type");
            params.put("type", type);
        }
        if (creditAccountId != null) {
            sql.append(" AND (v.credit_account_id = :creditAccountId OR v.voucher_id IN (")
                    .append(BANK_SIDE_OF_A_LOAN).append("))");
            params.put("creditAccountId", creditAccountId);
        }
        if (partyId != null) {
            sql.append(" AND v.obligor_party_id = :partyId");
            params.put("partyId", partyId);
        }
    }

    /**
     * El alcance por sucursal.
     *
     * <p>{@code SIN_SUCURSAL} no es un código: es la ausencia de uno. Se pide con {@code IS NULL}
     * porque {@code org_unit_code IN ('SIN_SUCURSAL')} no casaría con ninguna fila y la pantalla
     * mostraría vacío en vez de los créditos anteriores al sellado — que es justo el hueco que hay
     * que ver.
     */
    private void appendUnitScope(StringBuilder sql, Map<String, Object> params,
                                 List<String> unitCodes, String column) {
        appendScope(sql, params, unitCodes, column, SIN_SUCURSAL, "unitCodes");
    }

    /** The scope by product: {@code INSTITUCION} is not a product but the absence of one, asked with IS NULL. */
    private void appendProductScope(StringBuilder sql, Map<String, Object> params,
                                    List<String> productTypes, String column) {
        appendScope(sql, params, productTypes, column, INSTITUCION, "productTypes");
    }

    /** A filter by a dimension's codes, where {@code noneCode} stands for the rows that have none. */
    private void appendScope(StringBuilder sql, Map<String, Object> params, List<String> codes, String column,
                             String noneCode, String param) {
        if (codes == null || codes.isEmpty()) return;
        boolean none = codes.contains(noneCode);
        List<String> real = codes.stream().filter(c -> !noneCode.equals(c)).toList();

        if (none && real.isEmpty()) {
            sql.append(" AND ").append(column).append(" IS NULL");
        } else if (none) {
            sql.append(" AND (").append(column).append(" IN :").append(param).append(" OR ").append(column)
                    .append(" IS NULL)");
            params.put(param, real);
        } else {
            sql.append(" AND ").append(column).append(" IN :").append(param);
            params.put(param, real);
        }
    }

    /**
     * Los renglones de una póliza, ya de un solo lado.
     *
     * <p>{@code journal_entries} guarda pares (una fila = un cargo y su abono). La consola necesita
     * renglones sueltos, así que se agregan por cuenta dentro de la póliza con un UNION: cada par
     * aporta su cargo a una cuenta y su abono a otra. Un pago que liquida capital e intereses son dos
     * pares y salen como tres renglones —caja, capital, intereses—, que es como se lee una póliza.
     */
    public List<VoucherLineView> linesOf(UUID voucherId) {
        Query q = em.createNativeQuery("""
                SELECT m.code, a.name, a.type, SUM(m.debit), SUM(m.credit), MIN(m.description)
                  FROM (
                        SELECT e.debit_account AS code, e.amount AS debit, 0::numeric AS credit,
                               e.description
                          FROM accounting.journal_entries e WHERE e.voucher_id = :voucherId
                        UNION ALL
                        SELECT e.credit_account, 0::numeric, e.amount, e.description
                          FROM accounting.journal_entries e WHERE e.voucher_id = :voucherId
                       ) m
                  JOIN accounting.ledger_accounts a ON a.code = m.code
                 GROUP BY m.code, a.name, a.type
                 -- Cargos primero y por código: es el orden en que se lee una póliza en papel.
                 ORDER BY CASE WHEN SUM(m.debit) > 0 THEN 0 ELSE 1 END, m.code
                """);
        q.setParameter("voucherId", voucherId);
        @SuppressWarnings("unchecked") List<Object[]> rows = q.getResultList();
        return rows.stream()
                .map(r -> new VoucherLineView(str(r[0]), str(r[1]), str(r[2]),
                        dec(r[3]), dec(r[4]), str(r[5])))
                .toList();
    }

    public VoucherView voucherById(UUID voucherId) {
        Query q = em.createNativeQuery("""
                SELECT v.voucher_id, v.voucher_type, v.folio, v.period, v.voucher_date, v.org_unit_code,
                       v.concept, v.trigger_event, v.source_event_id, v.credit_account_id, v.obligor_party_id,
                       v.total_debit, v.total_credit, v.status, v.is_late_posting, v.original_period,
                       v.reversal_ref, v.product_type
                  FROM accounting.vouchers v WHERE v.voucher_id = :voucherId
                """);
        q.setParameter("voucherId", voucherId);
        @SuppressWarnings("unchecked") List<Object[]> rows = q.getResultList();
        if (rows.isEmpty()) return null;
        return voucherOf(rows.get(0), linesOf(voucherId));
    }

    // ── Balanza ──────────────────────────────────────────────────────────────

    public List<TrialBalanceRowView> trialBalance(String period, List<String> unitCodes) {
        return trialBalance(period, unitCodes, List.of());
    }

    /** The trial balance of the branches and products asked for; none asked, all of them. */
    public List<TrialBalanceRowView> trialBalance(String period, List<String> unitCodes, List<String> productTypes) {
        boolean byPeriod = period != null && !period.isBlank();
        // Periods are YYYYMM, so they compare as text: what came before the period is its opening balance.
        StringBuilder sql = new StringBuilder(byPeriod ? """
                SELECT a.code, a.name, a.type,
                       COALESCE(SUM(m.debit) FILTER (WHERE m.period = :period), 0),
                       COALESCE(SUM(m.credit) FILTER (WHERE m.period = :period), 0),
                       COALESCE(SUM(m.debit - m.credit) FILTER (WHERE m.period < :period), 0)
                """ : """
                SELECT a.code, a.name, a.type,
                       COALESCE(SUM(m.debit), 0), COALESCE(SUM(m.credit), 0), 0::numeric
                """);
        sql.append("""
                  FROM (
                        SELECT e.debit_account AS code, e.amount AS debit, 0::numeric AS credit,
                               e.period, e.org_unit_code, e.product_type
                          FROM accounting.journal_entries e
                        UNION ALL
                        SELECT e.credit_account, 0::numeric, e.amount, e.period, e.org_unit_code, e.product_type
                          FROM accounting.journal_entries e
                       ) m
                  JOIN accounting.ledger_accounts a ON a.code = m.code
                 WHERE 1=1""");
        Map<String, Object> params = new LinkedHashMap<>();
        if (byPeriod) {
            sql.append(" AND m.period <= :period");
            params.put("period", period);
        }
        appendUnitScope(sql, params, unitCodes, "m.org_unit_code");
        appendProductScope(sql, params, productTypes, "m.product_type");
        sql.append(" GROUP BY a.code, a.name, a.type ORDER BY a.code");

        Query q = em.createNativeQuery(sql.toString());
        params.forEach(q::setParameter);
        @SuppressWarnings("unchecked") List<Object[]> rows = q.getResultList();
        return rows.stream()
                .map(r -> {
                    BigDecimal d = dec(r[3]);
                    BigDecimal c = dec(r[4]);
                    BigDecimal opening = dec(r[5]);
                    return new TrialBalanceRowView(str(r[0]), str(r[1]), str(r[2]), d, c, d.subtract(c),
                            opening, opening.add(d).subtract(c));
                })
                .toList();
    }

    /**
     * The trial balance of {@code period} by product and branch: what the close keeps next to the whole one, so a closed
     * month shows what it closed with. Only what a product·branch moved in the period or still holds.
     */
    public List<DimensionBalanceView> trialBalanceByProduct(String period) {
        Query q = em.createNativeQuery("""
                SELECT m.product_type, m.org_unit_code, a.code, a.name, a.type,
                       COALESCE(SUM(m.debit) FILTER (WHERE m.period = :period), 0),
                       COALESCE(SUM(m.credit) FILTER (WHERE m.period = :period), 0),
                       COALESCE(SUM(m.debit - m.credit) FILTER (WHERE m.period < :period), 0)
                  FROM (
                        SELECT e.debit_account AS code, e.amount AS debit, 0::numeric AS credit,
                               e.period, e.org_unit_code, e.product_type
                          FROM accounting.journal_entries e WHERE e.period <= :period
                        UNION ALL
                        SELECT e.credit_account, 0::numeric, e.amount, e.period, e.org_unit_code, e.product_type
                          FROM accounting.journal_entries e WHERE e.period <= :period
                       ) m
                  JOIN accounting.ledger_accounts a ON a.code = m.code
                 GROUP BY m.product_type, m.org_unit_code, a.code, a.name, a.type
                HAVING COALESCE(SUM(m.debit + m.credit) FILTER (WHERE m.period = :period), 0) <> 0
                    OR SUM(m.debit - m.credit) <> 0
                 ORDER BY m.product_type NULLS FIRST, m.org_unit_code NULLS FIRST, a.code
                """);
        q.setParameter("period", period);
        @SuppressWarnings("unchecked") List<Object[]> rows = q.getResultList();
        return rows.stream()
                .map(r -> {
                    BigDecimal d = dec(r[5]);
                    BigDecimal c = dec(r[6]);
                    BigDecimal opening = dec(r[7]);
                    return new DimensionBalanceView(str(r[0]), str(r[1]), str(r[2]), str(r[3]), str(r[4]), d, c,
                            opening, opening.add(d).subtract(c));
                })
                .toList();
    }

    /**
     * A product's accounting scheme, as the ledger booked it: each concept (the kind of fact) with the pairs of accounts
     * it moved, how many entries and how much. {@code INSTITUCION} is the scheme of what belongs to no product.
     */
    public ProductSchemeView productScheme(String productType, String from, String to) {
        Map<String, Object> params = new LinkedHashMap<>();
        StringBuilder scope = new StringBuilder();
        if (INSTITUCION.equals(productType)) {
            scope.append(" AND e.product_type IS NULL");
        } else {
            scope.append(" AND e.product_type = :product");
            params.put("product", productType);
        }
        if (from != null && !from.isBlank()) {
            scope.append(" AND e.period >= :from");
            params.put("from", from);
        }
        if (to != null && !to.isBlank()) {
            scope.append(" AND e.period <= :to");
            params.put("to", to);
        }
        Query pairs = em.createNativeQuery("""
                SELECT e.trigger_event, e.debit_account, da.name, e.credit_account, ca.name,
                       COUNT(*), SUM(e.amount), MIN(e.description), COUNT(DISTINCT e.voucher_id)
                  FROM accounting.journal_entries e
                  JOIN accounting.ledger_accounts da ON da.code = e.debit_account
                  JOIN accounting.ledger_accounts ca ON ca.code = e.credit_account
                 WHERE 1=1""" + scope + """

                 GROUP BY e.trigger_event, e.debit_account, da.name, e.credit_account, ca.name
                 ORDER BY e.trigger_event, SUM(e.amount) DESC, e.debit_account, e.credit_account""");
        params.forEach(pairs::setParameter);
        // The concept a kind of fact is booked with: its vouchers' most frequent text.
        Query concepts = em.createNativeQuery("""
                SELECT e.trigger_event, MODE() WITHIN GROUP (ORDER BY v.concept), COUNT(DISTINCT e.voucher_id),
                       SUM(e.amount)
                  FROM accounting.journal_entries e
                  JOIN accounting.vouchers v ON v.voucher_id = e.voucher_id
                 WHERE 1=1""" + scope + """

                 GROUP BY e.trigger_event""");
        params.forEach(concepts::setParameter);

        Map<String, Object[]> byTrigger = new LinkedHashMap<>();
        @SuppressWarnings("unchecked") List<Object[]> conceptRows = concepts.getResultList();
        conceptRows.forEach(r -> byTrigger.put(str(r[0]), r));
        Map<String, List<SchemePairView>> pairsByTrigger = new LinkedHashMap<>();
        @SuppressWarnings("unchecked") List<Object[]> pairRows = pairs.getResultList();
        for (Object[] r : pairRows) {
            pairsByTrigger.computeIfAbsent(str(r[0]), k -> new ArrayList<>())
                    .add(new SchemePairView(str(r[1]), str(r[2]), str(r[3]), str(r[4]), num(r[5]), dec(r[6]), str(r[7])));
        }
        List<SchemeConceptView> out = pairsByTrigger.entrySet().stream()
                .map(e -> {
                    Object[] c = byTrigger.get(e.getKey());
                    return new SchemeConceptView(e.getKey(), c == null ? null : str(c[1]), c == null ? 0 : num(c[2]),
                            c == null ? BigDecimal.ZERO : dec(c[3]), List.copyOf(e.getValue()));
                })
                .toList();
        return new ProductSchemeView(productType, from, to, out);
    }

    // ── Resumen y sucursales ─────────────────────────────────────────────────

    /**
     * El movimiento de cada sucursal en el período, en una consulta.
     *
     * <p>Las cuentas de orden se excluyen de cargos y abonos: registran el compromiso de una línea
     * autorizada, cuadran entre sí y no forman parte del balance. Sumadas, una sucursal que autorizó
     * mucho y colocó poco aparecería como la de mayor movimiento.
     */
    public List<UnitMovementView> unitMovements(String period, List<String> unitCodes) {
        return movements("v.org_unit_code", period, unitCodes).stream()
                .map(r -> new UnitMovementView(str(r[0]), num(r[1]), num(r[2]),
                        // Cargos = abonos por construcción (cada par aporta lo mismo a los dos lados),
                        // así que la misma suma sirve para las dos columnas.
                        dec(r[3]), dec(r[3]), dec(r[4]), dec(r[5]), dec(r[6]), dec(r[7])))
                .toList();
    }

    /** The movement of each product in the period, measured as a branch's; the institution's own facts apart. */
    public List<ProductMovementView> productMovements(String period, List<String> unitCodes) {
        return movements("v.product_type", period, unitCodes).stream()
                .map(r -> new ProductMovementView(r[0] == null ? INSTITUCION : str(r[0]), num(r[1]), num(r[2]),
                        dec(r[3]), dec(r[3]), dec(r[4]), dec(r[5]), dec(r[6]), dec(r[7])))
                .toList();
    }

    /** The movement of the period grouped by one dimension of the voucher ({@code groupColumn}). */
    private List<Object[]> movements(String groupColumn, String period, List<String> unitCodes) {
        StringBuilder sql = new StringBuilder("SELECT " + groupColumn + ",").append("""

                       COUNT(DISTINCT v.voucher_id),
                       COUNT(DISTINCT v.credit_account_id),
                       COALESCE(SUM(CASE WHEN a.type <> 'ORDER' THEN e.amount ELSE 0 END), 0),
                       COALESCE(SUM(CASE WHEN e.credit_account IN ('4101', '4106') THEN e.amount
                                         WHEN e.debit_account  IN ('4101', '4106') THEN -e.amount ELSE 0 END), 0),
                       COALESCE(SUM(CASE WHEN e.credit_account = '4102' THEN e.amount
                                         WHEN e.debit_account  = '4102' THEN -e.amount ELSE 0 END), 0),
                       COALESCE(SUM(CASE WHEN e.credit_account = '4103' THEN e.amount
                                         WHEN e.debit_account  = '4103' THEN -e.amount ELSE 0 END), 0),
                       COALESCE(SUM(CASE WHEN e.credit_account = '1290' THEN e.amount
                                         WHEN e.debit_account  = '1290' THEN -e.amount ELSE 0 END), 0)
                  FROM accounting.vouchers v
                  JOIN accounting.journal_entries e ON e.voucher_id = v.voucher_id
                  JOIN accounting.ledger_accounts a ON a.code = e.debit_account
                 WHERE 1=1""");
        Map<String, Object> params = new LinkedHashMap<>();
        if (period != null && !period.isBlank()) {
            sql.append(" AND v.period = :period");
            params.put("period", period);
        }
        appendUnitScope(sql, params, unitCodes, "v.org_unit_code");
        sql.append(" GROUP BY ").append(groupColumn).append(" ORDER BY 4 DESC");

        Query q = em.createNativeQuery(sql.toString());
        params.forEach(q::setParameter);
        @SuppressWarnings("unchecked") List<Object[]> rows = q.getResultList();
        return rows;
    }

    /** Los totales del período. Una consulta, no la suma en memoria de las sucursales. */
    public Object[] periodTotals(String period, List<String> unitCodes) {
        StringBuilder sql = new StringBuilder("""
                SELECT COUNT(DISTINCT v.voucher_id),
                       COALESCE(SUM(CASE WHEN a.type <> 'ORDER' THEN e.amount ELSE 0 END), 0),
                       COALESCE(SUM(CASE WHEN a.type =  'ORDER' THEN e.amount ELSE 0 END), 0),
                       COALESCE(SUM(CASE WHEN e.credit_account IN ('4101', '4106') THEN e.amount
                                         WHEN e.debit_account  IN ('4101', '4106') THEN -e.amount ELSE 0 END), 0),
                       COALESCE(SUM(CASE WHEN e.credit_account = '4102' THEN e.amount
                                         WHEN e.debit_account  = '4102' THEN -e.amount ELSE 0 END), 0),
                       COALESCE(SUM(CASE WHEN e.credit_account = '4103' THEN e.amount
                                         WHEN e.debit_account  = '4103' THEN -e.amount ELSE 0 END), 0),
                       COALESCE(SUM(CASE WHEN e.credit_account = '1290' THEN e.amount
                                         WHEN e.debit_account  = '1290' THEN -e.amount ELSE 0 END), 0),
                       COALESCE(SUM(CASE WHEN e.debit_account  = '5102' THEN e.amount ELSE 0 END), 0),
                       COALESCE(SUM(CASE WHEN e.credit_account = '4104' THEN e.amount ELSE 0 END), 0),
                       COUNT(DISTINCT CASE WHEN v.trigger_event = 'ACCOUNT_ACTIVATED'
                                           THEN v.voucher_id END),
                       -- IVA trasladado del período. Va aparte de los ingresos porque NO lo es: es
                       -- dinero del SAT que se cobra y se retiene. Hace falta para conciliar contra
                       -- lo facturado, que sí lo incluye: sin él, la comparación resta el total de
                       -- un CFDI con IVA contra un devengado sin IVA y la diferencia que aparece es
                       -- exactamente el impuesto, no un faltante.
                       COALESCE(SUM(CASE WHEN e.credit_account = '2110' THEN e.amount
                                         WHEN e.debit_account  = '2110' THEN -e.amount ELSE 0 END), 0)
                  FROM accounting.vouchers v
                  JOIN accounting.journal_entries e ON e.voucher_id = v.voucher_id
                  JOIN accounting.ledger_accounts a ON a.code = e.debit_account
                 WHERE 1=1""");
        Map<String, Object> params = new LinkedHashMap<>();
        if (period != null && !period.isBlank()) {
            sql.append(" AND v.period = :period");
            params.put("period", period);
        }
        appendUnitScope(sql, params, unitCodes, "v.org_unit_code");

        Query q = em.createNativeQuery(sql.toString());
        params.forEach(q::setParameter);
        return (Object[]) q.getSingleResult();
    }

    /**
     * The bridge from recognized revenue to what was charged and has to be invoiced, for a period. What was charged is
     * the invoiceable items (one per charge, IVA its own item); the rest comes from the ledger.
     */
    public BillingBridgeView billingBridge(String period, List<String> unitCodes) {
        StringBuilder ledger = new StringBuilder("""
                SELECT COALESCE(SUM(CASE WHEN e.credit_account IN ('4101', '4102', '4103', '4106') THEN e.amount
                                         WHEN e.debit_account  IN ('4101', '4102', '4103', '4106') THEN -e.amount ELSE 0 END), 0),
                       COALESCE(SUM(CASE WHEN e.credit_account = '2110' THEN e.amount
                                         WHEN e.debit_account  = '2110' THEN -e.amount ELSE 0 END), 0),
                       COALESCE(SUM(CASE WHEN e.debit_account = '7401' THEN e.amount ELSE 0 END), 0),
                       COALESCE(SUM(CASE WHEN e.credit_account = '2150' AND e.debit_account = '1203' THEN e.amount
                                         WHEN e.debit_account = '2150' AND e.credit_account = '1203' THEN -e.amount ELSE 0 END), 0),
                       COALESCE(SUM(CASE WHEN e.debit_account = '2150' AND e.credit_account = '4106' THEN e.amount ELSE 0 END), 0),
                       COALESCE(SUM(CASE WHEN e.debit_account = '4106' AND e.credit_account = '1205' THEN e.amount ELSE 0 END), 0)
                  FROM accounting.journal_entries e
                 WHERE e.period = :period""");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("period", period);
        appendUnitScope(ledger, params, unitCodes, "e.org_unit_code");
        Query q = em.createNativeQuery(ledger.toString());
        params.forEach(q::setParameter);
        Object[] r = (Object[]) q.getSingleResult();

        StringBuilder items = new StringBuilder("""
                SELECT COALESCE(SUM(i.amount), 0)
                  FROM accounting.invoiceable_items i
                  LEFT JOIN accounting.account_balance_shadows s ON s.credit_account_id = i.credit_account_id
                 WHERE i.period = :period""");
        Map<String, Object> itemParams = new LinkedHashMap<>();
        itemParams.put("period", period);
        appendUnitScope(items, itemParams, unitCodes, "s.org_unit_code");
        Query qi = em.createNativeQuery(items.toString());
        itemParams.forEach(qi::setParameter);
        BigDecimal charged = dec(qi.getSingleResult());

        BigDecimal revenue = dec(r[0]), iva = dec(r[1]), stage3 = dec(r[2]), feesCharged = dec(r[3]),
                feesRecognized = dec(r[4]), costs = dec(r[5]);
        BigDecimal explained = revenue.add(iva).add(stage3).add(feesCharged).subtract(feesRecognized).add(costs);
        return new BillingBridgeView(charged, revenue, iva, stage3, feesCharged, feesRecognized, costs,
                charged.subtract(explained));
    }

    // ── Auxiliar de un préstamo ──────────────────────────────────────────────

    /** Saldos acumulados por cuenta de un crédito. Sin corte por período: el auxiliar no se corta. */
    public List<LedgerBalanceView> loanBalances(UUID creditAccountId) {
        Query q = em.createNativeQuery("""
                SELECT a.code, a.name, a.type,
                       COALESCE(SUM(m.debit), 0), COALESCE(SUM(m.credit), 0)
                  FROM (
                        SELECT e.debit_account AS code, e.amount AS debit, 0::numeric AS credit,
                               e.credit_account_id
                          FROM accounting.journal_entries e
                        UNION ALL
                        SELECT e.credit_account, 0::numeric, e.amount, e.credit_account_id
                          FROM accounting.journal_entries e
                       ) m
                  JOIN accounting.ledger_accounts a ON a.code = m.code
                 WHERE m.credit_account_id = :creditAccountId
                 GROUP BY a.code, a.name, a.type ORDER BY a.code
                """);
        q.setParameter("creditAccountId", creditAccountId);
        @SuppressWarnings("unchecked") List<Object[]> rows = q.getResultList();
        return rows.stream()
                .map(r -> {
                    BigDecimal d = dec(r[3]);
                    BigDecimal c = dec(r[4]);
                    return new LedgerBalanceView(str(r[0]), str(r[1]), str(r[2]), d, c, d.subtract(c));
                })
                .toList();
    }

    // ── Períodos ─────────────────────────────────────────────────────────────

    public List<PeriodView> periods() {
        Query q = em.createNativeQuery("""
                SELECT p.period, p.status, p.closed_at,
                       (SELECT COUNT(*) FROM accounting.vouchers v WHERE v.period = p.period)
                  FROM accounting.accounting_periods p
                 ORDER BY p.period DESC
                """);
        @SuppressWarnings("unchecked") List<Object[]> rows = q.getResultList();
        return rows.stream()
                .map(r -> new PeriodView(str(r[0]), str(r[1]), instant(r[2]), num(r[3])))
                .toList();
    }

    // ── Atribución de sucursal a lo ya asentado ──────────────────────────────

    /**
     * Le pone a las pólizas de un crédito la sucursal que le acaba de sellar cartera.
     *
     * <p><b>Esto no reescribe historia, la completa.</b> La sucursal de origen es una propiedad
     * <i>inmutable del préstamo</i>, no del día en que se asentó: si el crédito lo colocó Culiacán,
     * lo colocó Culiacán también el mes pasado. Lo que no existía era el dato, y por eso las pólizas
     * anteriores al sellado salieron sin él.
     *
     * <p>Lo que sí sería reescribir historia es recalcular la sucursal cuando la cartera se reasigna
     * — y eso no puede pasar porque el sello no se sobrescribe: sólo se rellena lo que está en nulo.
     */
    @Transactional
    public int attributeUnit(UUID creditAccountId, String orgUnitCode) {
        int vouchers = em.createNativeQuery("""
                UPDATE accounting.vouchers SET org_unit_code = :code
                 WHERE credit_account_id = :id AND org_unit_code IS NULL
                """).setParameter("code", orgUnitCode).setParameter("id", creditAccountId).executeUpdate();

        em.createNativeQuery("""
                UPDATE accounting.journal_entries SET org_unit_code = :code
                 WHERE credit_account_id = :id AND org_unit_code IS NULL
                """).setParameter("code", orgUnitCode).setParameter("id", creditAccountId).executeUpdate();

        em.createNativeQuery("""
                UPDATE accounting.account_balance_shadows SET org_unit_code = :code
                 WHERE credit_account_id = :id AND org_unit_code IS NULL
                """).setParameter("code", orgUnitCode).setParameter("id", creditAccountId).executeUpdate();

        return vouchers;
    }

    /**
     * Le pone sucursal a toda póliza cuyo crédito ya la tenga conocida.
     *
     * <p><b>Por qué existe además de la atribución por crédito.</b> La reconciliación del canal
     * recorre las cuentas paginando cartera, y lo que no caiga en esa página se queda sin atribuir —
     * en la última corrida, 30 pólizas de 5 créditos—. El síntoma es peor que el hueco: la consola
     * enseña «Sin sucursal» sobre créditos que sí tienen una, y eso se lee como que la sucursal no
     * se está sellando.
     *
     * <p>Esto no pagina ni itera: el shadow ya sabe la sucursal de cada crédito, así que es un solo
     * UPDATE correlacionado. Sólo rellena lo que está en nulo, así que es idempotente y no puede
     * reescribir una atribución existente.
     */
    @Transactional
    public int sweepUnits() {
        int vouchers = em.createNativeQuery("""
                UPDATE accounting.vouchers v SET org_unit_code = s.org_unit_code
                  FROM accounting.account_balance_shadows s
                 WHERE v.credit_account_id = s.credit_account_id
                   AND v.org_unit_code IS NULL
                   AND s.org_unit_code IS NOT NULL
                """).executeUpdate();

        em.createNativeQuery("""
                UPDATE accounting.journal_entries e SET org_unit_code = s.org_unit_code
                  FROM accounting.account_balance_shadows s
                 WHERE e.credit_account_id = s.credit_account_id
                   AND e.org_unit_code IS NULL
                   AND s.org_unit_code IS NOT NULL
                """).executeUpdate();
        return vouchers;
    }

    // ── Pólizas agrupadas por préstamo ───────────────────────────────────────

    /** Cuántos préstamos distintos tuvieron movimiento en el período. Es el total de la paginación. */
    public long countLoans(String period, List<String> unitCodes) {
        StringBuilder sql = new StringBuilder("""
                SELECT COUNT(DISTINCT v.credit_account_id)
                  FROM accounting.vouchers v
                 WHERE v.credit_account_id IS NOT NULL""");
        Map<String, Object> params = new LinkedHashMap<>();
        if (period != null && !period.isBlank()) {
            sql.append(" AND v.period = :period");
            params.put("period", period);
        }
        appendUnitScope(sql, params, unitCodes, "v.org_unit_code");
        Query q = em.createNativeQuery(sql.toString());
        params.forEach(q::setParameter);
        return ((Number) q.getSingleResult()).longValue();
    }

    /**
     * El movimiento del período agrupado por préstamo, ordenado por importe.
     *
     * <p>El {@code JOIN} a {@code ledger_accounts} va por la cuenta de <b>cargo</b>, igual que en
     * {@link #unitMovements}: cada renglón del mayor es un par, así que clasificarlo por su cargo
     * cuenta el importe una sola vez. Unir por las dos cuentas duplicaría cada asiento.
     *
     * <p>Las cuentas de orden se separan de los cargos patrimoniales porque registran la línea
     * autorizada y no capital colocado. Sumadas, un crédito autorizado por medio millón y dispuesto
     * en cincuenta mil encabezaría la lista por dinero que nunca salió.
     */
    public List<LoanMovementView> loanMovements(String period, List<String> unitCodes,
                                                 int page, int size) {
        StringBuilder sql = new StringBuilder("""
                SELECT v.credit_account_id,
                       MIN(v.obligor_party_id::text),
                       MIN(v.org_unit_code),
                       COUNT(DISTINCT v.voucher_id),
                       MIN(v.voucher_date),
                       MAX(v.voucher_date),
                       COALESCE(SUM(CASE WHEN a.type <> 'ORDER' THEN e.amount ELSE 0 END), 0),
                       COALESCE(SUM(CASE WHEN a.type =  'ORDER' THEN e.amount ELSE 0 END), 0),
                       COALESCE(SUM(CASE WHEN e.credit_account IN ('4101', '4106') THEN e.amount
                                         WHEN e.debit_account  IN ('4101', '4106') THEN -e.amount ELSE 0 END), 0),
                       COALESCE(SUM(CASE WHEN e.credit_account = '4102' THEN e.amount
                                         WHEN e.debit_account  = '4102' THEN -e.amount ELSE 0 END), 0),
                       COALESCE(SUM(CASE WHEN e.credit_account = '4103' THEN e.amount
                                         WHEN e.debit_account  = '4103' THEN -e.amount ELSE 0 END), 0),
                       COALESCE(SUM(CASE WHEN e.credit_account = '1290' THEN e.amount
                                         WHEN e.debit_account  = '1290' THEN -e.amount ELSE 0 END), 0),
                       COALESCE(SUM(CASE WHEN e.debit_account = '5102' THEN e.amount ELSE 0 END), 0),
                       COALESCE(SUM(CASE WHEN e.debit_account = '1201' AND e.credit_account = '1101'
                                         THEN e.amount ELSE 0 END), 0)
                  FROM accounting.vouchers v
                  JOIN accounting.journal_entries e ON e.voucher_id = v.voucher_id
                  JOIN accounting.ledger_accounts a ON a.code = e.debit_account
                 WHERE v.credit_account_id IS NOT NULL""");
        Map<String, Object> params = new LinkedHashMap<>();
        if (period != null && !period.isBlank()) {
            sql.append(" AND v.period = :period");
            params.put("period", period);
        }
        appendUnitScope(sql, params, unitCodes, "v.org_unit_code");
        // Por importe patrimonial: lo que más movió el balance va primero. Ordenar por fecha
        // dejaría arriba al crédito que devengó de madrugada, que no es lo que se busca al conciliar.
        sql.append(" GROUP BY v.credit_account_id ORDER BY 7 DESC, 4 DESC LIMIT :limit OFFSET :offset");
        params.put("limit", size);
        params.put("offset", (long) page * size);

        Query q = em.createNativeQuery(sql.toString());
        params.forEach(q::setParameter);
        @SuppressWarnings("unchecked") List<Object[]> rows = q.getResultList();
        return rows.stream()
                .map(r -> new LoanMovementView(
                        (UUID) r[0],
                        r[1] == null ? null : UUID.fromString(r[1].toString()),
                        str(r[2]), num(r[3]), instant(r[4]), instant(r[5]),
                        dec(r[6]), dec(r[7]), dec(r[8]), dec(r[9]), dec(r[10]),
                        dec(r[11]), dec(r[12]), dec(r[13])))
                .toList();
    }

    // ── Conversión ───────────────────────────────────────────────────────────

    private static VoucherView voucherOf(Object[] r, List<VoucherLineView> lines) {
        return new VoucherView(
                (UUID) r[0], str(r[1]), num(r[2]), str(r[3]), instant(r[4]), str(r[5]),
                str(r[6]), str(r[7]), str(r[8]), (UUID) r[9], (UUID) r[10],
                dec(r[11]), dec(r[12]), str(r[13]),
                Boolean.TRUE.equals(r[14]), str(r[15]), (UUID) r[16], str(r[17]),
                lines);
    }

    private static String str(Object o)      { return o == null ? null : o.toString(); }
    private static long num(Object o)        { return o == null ? 0L : ((Number) o).longValue(); }
    private static BigDecimal dec(Object o)  { return o == null ? BigDecimal.ZERO : (BigDecimal) o; }

    private static Instant instant(Object o) {
        if (o == null) return null;
        if (o instanceof Instant i) return i;
        if (o instanceof Timestamp t) return t.toInstant();
        if (o instanceof java.time.OffsetDateTime odt) return odt.toInstant();
        return null;
    }
}

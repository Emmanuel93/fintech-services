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
                       v.reversal_ref
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
            sql.append(" AND v.credit_account_id = :creditAccountId");
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
        if (unitCodes == null || unitCodes.isEmpty()) return;
        boolean sinSucursal = unitCodes.contains(SIN_SUCURSAL);
        List<String> reales = unitCodes.stream().filter(c -> !SIN_SUCURSAL.equals(c)).toList();

        if (sinSucursal && reales.isEmpty()) {
            sql.append(" AND ").append(column).append(" IS NULL");
        } else if (sinSucursal) {
            sql.append(" AND (").append(column).append(" IN :unitCodes OR ").append(column).append(" IS NULL)");
            params.put("unitCodes", reales);
        } else {
            sql.append(" AND ").append(column).append(" IN :unitCodes");
            params.put("unitCodes", reales);
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
                       v.reversal_ref
                  FROM accounting.vouchers v WHERE v.voucher_id = :voucherId
                """);
        q.setParameter("voucherId", voucherId);
        @SuppressWarnings("unchecked") List<Object[]> rows = q.getResultList();
        if (rows.isEmpty()) return null;
        return voucherOf(rows.get(0), linesOf(voucherId));
    }

    // ── Balanza ──────────────────────────────────────────────────────────────

    public List<TrialBalanceRowView> trialBalance(String period, List<String> unitCodes) {
        StringBuilder sql = new StringBuilder("""
                SELECT a.code, a.name, a.type,
                       COALESCE(SUM(m.debit), 0), COALESCE(SUM(m.credit), 0)
                  FROM (
                        SELECT e.debit_account AS code, e.amount AS debit, 0::numeric AS credit,
                               e.period, e.org_unit_code
                          FROM accounting.journal_entries e
                        UNION ALL
                        SELECT e.credit_account, 0::numeric, e.amount, e.period, e.org_unit_code
                          FROM accounting.journal_entries e
                       ) m
                  JOIN accounting.ledger_accounts a ON a.code = m.code
                 WHERE 1=1""");
        Map<String, Object> params = new LinkedHashMap<>();
        if (period != null && !period.isBlank()) {
            sql.append(" AND m.period = :period");
            params.put("period", period);
        }
        appendUnitScope(sql, params, unitCodes, "m.org_unit_code");
        sql.append(" GROUP BY a.code, a.name, a.type ORDER BY a.code");

        Query q = em.createNativeQuery(sql.toString());
        params.forEach(q::setParameter);
        @SuppressWarnings("unchecked") List<Object[]> rows = q.getResultList();
        return rows.stream()
                .map(r -> {
                    BigDecimal d = dec(r[3]);
                    BigDecimal c = dec(r[4]);
                    return new TrialBalanceRowView(str(r[0]), str(r[1]), str(r[2]), d, c, d.subtract(c));
                })
                .toList();
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
        StringBuilder sql = new StringBuilder("""
                SELECT v.org_unit_code,
                       COUNT(DISTINCT v.voucher_id),
                       COUNT(DISTINCT v.credit_account_id),
                       COALESCE(SUM(CASE WHEN a.type <> 'ORDER' THEN e.amount ELSE 0 END), 0),
                       COALESCE(SUM(CASE WHEN e.credit_account = '4101' THEN e.amount
                                         WHEN e.debit_account  = '4101' THEN -e.amount ELSE 0 END), 0),
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
        sql.append(" GROUP BY v.org_unit_code ORDER BY 4 DESC");

        Query q = em.createNativeQuery(sql.toString());
        params.forEach(q::setParameter);
        @SuppressWarnings("unchecked") List<Object[]> rows = q.getResultList();
        return rows.stream()
                .map(r -> new UnitMovementView(str(r[0]), num(r[1]), num(r[2]),
                        // Cargos = abonos por construcción (cada par aporta lo mismo a los dos lados),
                        // así que la misma suma sirve para las dos columnas.
                        dec(r[3]), dec(r[3]), dec(r[4]), dec(r[5]), dec(r[6]), dec(r[7])))
                .toList();
    }

    /** Los totales del período. Una consulta, no la suma en memoria de las sucursales. */
    public Object[] periodTotals(String period, List<String> unitCodes) {
        StringBuilder sql = new StringBuilder("""
                SELECT COUNT(DISTINCT v.voucher_id),
                       COALESCE(SUM(CASE WHEN a.type <> 'ORDER' THEN e.amount ELSE 0 END), 0),
                       COALESCE(SUM(CASE WHEN a.type =  'ORDER' THEN e.amount ELSE 0 END), 0),
                       COALESCE(SUM(CASE WHEN e.credit_account = '4101' THEN e.amount
                                         WHEN e.debit_account  = '4101' THEN -e.amount ELSE 0 END), 0),
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
                       COALESCE(SUM(CASE WHEN e.credit_account = '4101' THEN e.amount
                                         WHEN e.debit_account  = '4101' THEN -e.amount ELSE 0 END), 0),
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
                Boolean.TRUE.equals(r[14]), str(r[15]), (UUID) r[16],
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

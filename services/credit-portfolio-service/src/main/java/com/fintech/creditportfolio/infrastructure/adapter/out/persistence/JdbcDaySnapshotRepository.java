package com.fintech.creditportfolio.infrastructure.adapter.out.persistence;

import java.util.Map;
import java.util.HashMap;
import java.util.ArrayList;
import java.time.temporal.ChronoUnit;
import java.math.BigDecimal;
import com.fintech.creditportfolio.domain.StatementCoverage;
import com.fintech.creditportfolio.application.port.out.DaySnapshotRepository;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class JdbcDaySnapshotRepository implements DaySnapshotRepository {

    private static final String COLUMNS = """
            business_date, credit_account_id, account_status, principal, accrued_interest, iva, penalty, total_debt,
            overdue_principal, overdue_installments, oldest_due_date, days_delinquent, balance_version,
            later_movements, taken_at, ledger_total, ledger_gap, interest_bearing_principal, scheduled_interest,
            statement_due_unpaid, scheduled_tax""";

    private static final RowMapper<AccountSnapshot> ACCOUNT = (rs, i) -> new AccountSnapshot(
            rs.getObject("business_date", LocalDate.class), rs.getObject("credit_account_id", UUID.class),
            rs.getString("account_status"), rs.getBigDecimal("principal"), rs.getBigDecimal("accrued_interest"),
            rs.getBigDecimal("iva"), rs.getBigDecimal("penalty"), rs.getBigDecimal("total_debt"),
            rs.getBigDecimal("overdue_principal"), rs.getInt("overdue_installments"),
            rs.getObject("oldest_due_date", LocalDate.class), rs.getInt("days_delinquent"),
            rs.getLong("balance_version"), rs.getInt("later_movements"), rs.getTimestamp("taken_at").toInstant(),
            rs.getBigDecimal("ledger_total"), rs.getBigDecimal("ledger_gap"), rs.getBigDecimal("interest_bearing_principal"),
            rs.getBigDecimal("scheduled_interest"), rs.getBigDecimal("statement_due_unpaid"),
            rs.getBigDecimal("scheduled_tax"));

    private static final RowMapper<DaySnapshot> DAY = (rs, i) -> new DaySnapshot(
            rs.getObject("business_date", LocalDate.class), rs.getString("status"), rs.getString("triggered_by"),
            rs.getObject("accounts", Integer.class), rs.getObject("delinquent_accounts", Integer.class),
            rs.getBigDecimal("principal_total"), rs.getBigDecimal("overdue_total"), rs.getString("waiting_for"),
            rs.getString("last_error"), rs.getInt("attempts"),
            rs.getTimestamp("started_at") != null ? rs.getTimestamp("started_at").toInstant() : null,
            rs.getTimestamp("completed_at") != null ? rs.getTimestamp("completed_at").toInstant() : null,
            rs.getObject("ledger_gaps", Integer.class));

    private final NamedParameterJdbcTemplate jdbc;

    JdbcDaySnapshotRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Claim claim(LocalDate businessDate, String triggeredBy, Duration staleAfter) {
        var p = new MapSqlParameterSource("d", businessDate).addValue("by", triggeredBy)
                .addValue("stale", staleAfter.toSeconds());
        int nuevo = jdbc.update("""
                INSERT INTO credit_portfolio.day_snapshots (business_date, status, triggered_by, attempts)
                VALUES (:d, 'RUNNING', :by, 1)
                ON CONFLICT (business_date) DO NOTHING""", p);
        if (nuevo == 1) return Claim.CLAIMED;
        int retomado = jdbc.update("""
                UPDATE credit_portfolio.day_snapshots
                   SET status = 'RUNNING', triggered_by = :by, started_at = now(), completed_at = NULL,
                       last_error = NULL, waiting_for = NULL, attempts = attempts + 1
                 WHERE business_date = :d
                   AND (status IN ('WAITING', 'FAILED')
                        OR (status = 'RUNNING' AND started_at < now() - make_interval(secs => :stale)))""", p);
        if (retomado == 1) return Claim.CLAIMED;
        return find(businessDate).filter(DaySnapshot::done).isPresent() ? Claim.ALREADY_DONE : Claim.IN_PROGRESS;
    }

    @Override
    public void waiting(LocalDate businessDate, String triggeredBy, String reason) {
        jdbc.update("""
                INSERT INTO credit_portfolio.day_snapshots (business_date, status, triggered_by, waiting_for)
                VALUES (:d, 'WAITING', :by, :r)
                ON CONFLICT (business_date) DO UPDATE
                   SET status = 'WAITING', triggered_by = :by, waiting_for = :r
                 WHERE credit_portfolio.day_snapshots.status IN ('WAITING', 'FAILED')""",
                new MapSqlParameterSource("d", businessDate).addValue("by", triggeredBy).addValue("r", reason));
    }

    /**
     * One statement for every account. Balances "at the end of D" are the live balance minus the
     * movements whose value date is after D. {@code balance_events} stores balances after each movement,
     * not deltas per bucket, so each delta is rebuilt against the previous movement ({@code lag}), and only
     * for the accounts that had something after D, which at 00:05 are few or none. IVA has no
     * balance-after column: only its charges are subtracted.
     */
    @Override
    public int take(LocalDate businessDate, Instant endOfDay) {
        int filas = insertSnapshot(businessDate, endOfDay);
        correctStatementDelinquency(businessDate);
        return filas;
    }

    /**
     * The delinquency of statement-billed accounts at D by {@link StatementCoverage}: each payment valued up to D covers
     * only the statements issued before it, oldest first. The insert above measures it cumulatively (everything paid
     * against everything asked), which let a statement paid in full pay ahead the minimums of the next ones; this
     * replaces those figures for every revolving account of the day.
     */
    private void correctStatementDelinquency(LocalDate d) {
        MapSqlParameterSource p = new MapSqlParameterSource("d", d);
        Map<UUID, List<StatementCoverage.Obligation>> obligations = new HashMap<>();
        jdbc.query("""
                SELECT i.schedule_id, i.installment_id, i.installment_number, i.cutoff_date, i.due_date, i.total_amount
                  FROM credit_portfolio.installments i
                  JOIN credit_portfolio.account_day_snapshots f ON f.credit_account_id = i.schedule_id AND f.business_date = :d
                  JOIN credit_portfolio.credit_accounts a ON a.credit_account_id = i.schedule_id
                 WHERE a.product_behavior = 'REVOLVING' AND i.status NOT IN ('REPLACED', 'CANCELLED')""", p, rs -> {
            obligations.computeIfAbsent(rs.getObject("schedule_id", UUID.class), k -> new ArrayList<>())
                    .add(new StatementCoverage.Obligation(rs.getObject("installment_id", UUID.class), rs.getInt("installment_number"),
                            rs.getObject("cutoff_date", LocalDate.class), rs.getObject("due_date", LocalDate.class),
                            rs.getBigDecimal("total_amount")));
        });
        if (obligations.isEmpty()) {
            return;
        }
        Map<UUID, Map<String, BigDecimal[]>> payments = new HashMap<>();
        Map<UUID, Map<String, LocalDate>> valueDates = new HashMap<>();
        jdbc.query("""
                SELECT e.credit_account_id, e.source_event_id, e.event_type, e.value_date, e.delta_amount
                  FROM credit_portfolio.balance_events e
                  JOIN credit_portfolio.account_day_snapshots f ON f.credit_account_id = e.credit_account_id AND f.business_date = :d
                  JOIN credit_portfolio.credit_accounts a ON a.credit_account_id = e.credit_account_id
                 WHERE a.product_behavior = 'REVOLVING'
                   AND e.event_type IN ('PAYMENT_APPLIED', 'PAYMENT_RETURNED', 'COMMISSION_NETTED', 'COMMISSION_NETTED_REVERSED',
                                        'AGREEMENT_QUITA_PARCIAL')
                   AND e.value_date <= :d""", p, rs -> {
            UUID account = rs.getObject("credit_account_id", UUID.class);
            String source = rs.getString("source_event_id");
            BigDecimal delta = rs.getBigDecimal("delta_amount");
            String type = rs.getString("event_type");
            // A distributor's commission netted against a statement covers it as her cash does; so does a quita.
            if ("PAYMENT_APPLIED".equals(type) || StatementCoverage.COMMISSION_NETTED.equals(type)
                    || StatementCoverage.AGREEMENT_QUITA_PARCIAL.equals(type)) {
                payments.computeIfAbsent(account, k -> new HashMap<>())
                        .computeIfAbsent(source, k -> new BigDecimal[]{BigDecimal.ZERO})[0] =
                        payments.get(account).get(source)[0].add(delta.negate());
                valueDates.computeIfAbsent(account, k -> new HashMap<>()).put(source, rs.getObject("value_date", LocalDate.class));
            } else if (source != null) {
                String order = source.replaceFirst("^PAYMENT_RETURNED:", "")
                        .replaceFirst("^" + StatementCoverage.COMMISSION_NETTED_REVERSED + ":", "");
                payments.computeIfAbsent(account, k -> new HashMap<>())
                        .computeIfAbsent(order, k -> new BigDecimal[]{BigDecimal.ZERO})[0] =
                        payments.get(account).get(order)[0].subtract(delta.abs());
            }
        });
        Map<UUID, BigDecimal> principal = new HashMap<>();
        jdbc.query("SELECT credit_account_id, principal FROM credit_portfolio.account_day_snapshots WHERE business_date = :d", p,
                rs -> { principal.put(rs.getObject("credit_account_id", UUID.class), rs.getBigDecimal("principal")); });
        // The principal each statement billed of the plans (a distributor's placements): what the moratorium runs on
        // when the statement is unpaid, never the interest, the IVA or the moratorium the statement carried too.
        Map<UUID, Map<Integer, BigDecimal>> billedPrincipal = new HashMap<>();
        jdbc.query("""
                SELECT d.credit_account_id, i.billed_cycle, sum(i.principal_amount) AS capital
                  FROM credit_portfolio.installments i
                  JOIN credit_portfolio.dispositions d ON d.disposition_id = i.schedule_id
                  JOIN credit_portfolio.account_day_snapshots f ON f.credit_account_id = d.credit_account_id AND f.business_date = :d
                 WHERE i.billed_cycle IS NOT NULL AND i.status NOT IN ('REPLACED', 'CANCELLED')
                 GROUP BY d.credit_account_id, i.billed_cycle""", p, rs -> {
            billedPrincipal.computeIfAbsent(rs.getObject("credit_account_id", UUID.class), k -> new HashMap<>())
                    .put(rs.getInt("billed_cycle"), rs.getBigDecimal("capital"));
        });

        List<MapSqlParameterSource> updates = new ArrayList<>();
        obligations.forEach((account, accountObligations) -> {
            List<StatementCoverage.Payment> net = new ArrayList<>();
            payments.getOrDefault(account, Map.of()).forEach((key, amount) -> {
                LocalDate valueDate = valueDates.getOrDefault(account, Map.of()).get(key);
                if (valueDate != null && amount[0].signum() > 0) {
                    net.add(new StatementCoverage.Payment(key, valueDate, amount[0]));
                }
            });
            Map<UUID, BigDecimal> covered = StatementCoverage.cover(accountObligations, net, d);
            BigDecimal overdue = BigDecimal.ZERO;
            BigDecimal overdueAmount = BigDecimal.ZERO;
            BigDecimal dueUnpaid = BigDecimal.ZERO;
            int overdueCount = 0;
            LocalDate oldestDue = null;
            for (StatementCoverage.Obligation o : accountObligations) {
                if (o.due().isAfter(d)) continue;
                BigDecimal missing = o.amount().subtract(covered.getOrDefault(o.id(), BigDecimal.ZERO));
                if (o.due().equals(d)) {
                    // Due today: not late yet, but unpaid when the day ends (a statement cut today asks for it).
                    if (missing.compareTo(CENTAVO) >= 0) dueUnpaid = dueUnpaid.add(missing);
                    continue;
                }
                if (missing.compareTo(CENTAVO) >= 0) {
                    // The moratorium runs on the principal the statement billed; what she sees as arrears is the whole.
                    overdue = overdue.add(StatementCoverage.overduePrincipal(o, missing,
                            billedPrincipal.getOrDefault(account, Map.of()).get(o.cycle())));
                    overdueAmount = overdueAmount.add(missing);
                    overdueCount++;
                    if (oldestDue == null || o.due().isBefore(oldestDue)) oldestDue = o.due();
                }
            }
            overdue = overdue.min(principal.getOrDefault(account, BigDecimal.ZERO).max(BigDecimal.ZERO));
            if (overdue.compareTo(CENTAVO) < 0) {
                overdue = BigDecimal.ZERO;
                overdueCount = 0;
                oldestDue = null;
            }
            updates.add(new MapSqlParameterSource("d", d).addValue("a", account).addValue("v", overdue).addValue("n", overdueCount)
                    .addValue("m", oldestDue).addValue("dpd", oldestDue == null ? 0 : (int) ChronoUnit.DAYS.between(oldestDue, d))
                    .addValue("t", dueUnpaid).addValue("oa", overdueAmount));
        });
        jdbc.batchUpdate("""
                UPDATE credit_portfolio.account_day_snapshots
                   SET overdue_principal = :v, overdue_installments = :n, oldest_due_date = :m, days_delinquent = :dpd,
                       statement_due_unpaid = :t, overdue_statements_amount = :oa
                 WHERE business_date = :d AND credit_account_id = :a""", updates.toArray(MapSqlParameterSource[]::new));
    }

    private static final BigDecimal CENTAVO = new BigDecimal("0.01");

    private int insertSnapshot(LocalDate businessDate, Instant endOfDay) {
        return jdbc.update("""
                WITH tardios AS (
                    SELECT credit_account_id, min(applied_at) AS desde
                      FROM credit_portfolio.balance_events
                     WHERE value_date > :d
                     GROUP BY credit_account_id),
                ev AS (
                    SELECT e.credit_account_id, e.value_date, e.applied_at, e.balance_event_id, e.event_type,
                           e.delta_amount, e.principal_after, e.interest_after, e.penalty_after
                      FROM credit_portfolio.balance_events e
                      JOIN tardios t ON t.credit_account_id = e.credit_account_id
                     WHERE e.applied_at >= t.desde
                    UNION ALL
                    SELECT b.credit_account_id, b.value_date, b.applied_at, b.balance_event_id, b.event_type,
                           b.delta_amount, b.principal_after, b.interest_after, b.penalty_after
                      FROM tardios t
                      JOIN LATERAL (SELECT * FROM credit_portfolio.balance_events x
                                     WHERE x.credit_account_id = t.credit_account_id AND x.applied_at < t.desde
                                     ORDER BY x.applied_at DESC, x.balance_event_id DESC LIMIT 1) b ON true),
                deltas AS (
                    SELECT credit_account_id, value_date, event_type, delta_amount,
                           principal_after - coalesce(lag(principal_after) OVER w, 0) AS dp,
                           interest_after  - coalesce(lag(interest_after)  OVER w, 0) AS di,
                           penalty_after   - coalesce(lag(penalty_after)   OVER w, 0) AS dpen
                      FROM ev
                    WINDOW w AS (PARTITION BY credit_account_id ORDER BY applied_at, balance_event_id)),
                ajuste AS (
                    SELECT credit_account_id,
                           coalesce(sum(dp)   FILTER (WHERE value_date > :d), 0) AS dp,
                           coalesce(sum(di)   FILTER (WHERE value_date > :d), 0) AS di,
                           coalesce(sum(dpen) FILTER (WHERE value_date > :d), 0) AS dpen,
                           -- IVA has no after-balance column; each entry's amount is the measured sum of the four
                           -- buckets, so its IVA step is what is left after principal, interest and penalty. Only
                           -- counting CHARGE_IVA missed the IVA a later write-off or payment moved (a false gap).
                           coalesce(sum(delta_amount - dp - di - dpen) FILTER (WHERE value_date > :d), 0) AS diva,
                           count(*) FILTER (WHERE value_date > :d) AS n
                      FROM deltas
                     GROUP BY credit_account_id),
                calendarios AS (
                    -- A statement-billed product (behavior REVOLVING) owes an installment only once a statement
                    -- billed it; until then it is not overdue, whatever its own date says.
                    SELECT d.credit_account_id, d.disposition_id AS schedule_id, a.product_behavior = 'REVOLVING' AS por_estado
                      FROM credit_portfolio.dispositions d
                      JOIN credit_portfolio.credit_accounts a ON a.credit_account_id = d.credit_account_id
                    UNION ALL
                    SELECT credit_account_id, credit_account_id, product_behavior = 'REVOLVING' FROM credit_portfolio.credit_accounts),
                libro AS (
                    -- Subledger integrity: the sum of every entry valued up to D must be the debt at D.
                    SELECT credit_account_id, sum(delta_amount) AS total
                      FROM credit_portfolio.balance_events WHERE value_date <= :d GROUP BY credit_account_id),
                pendientes AS (
                    -- Unpaid installments and whether each is owed at the end of D.
                    SELECT c.credit_account_id, i.due_date, i.principal_amount,
                           (i.due_date < :d AND (NOT coalesce(c.por_estado, false) OR i.billed_cycle IS NOT NULL)) AS vencida
                      FROM calendarios c
                      JOIN credit_portfolio.installments i ON i.schedule_id = c.schedule_id
                     WHERE i.status IN ('PENDING', 'PARTIAL', 'OVERDUE')
                       AND NOT coalesce(c.por_estado, false)),
                capital_al_dia AS (
                    SELECT a.credit_account_id, a.principal_balance - coalesce(j.dp, 0) AS capital,
                           a.principal_balance - coalesce(j.dp, 0) + a.accrued_interest_balance - coalesce(j.di, 0)
                         + a.iva_balance - coalesce(j.diva, 0) + a.penalty_balance - coalesce(j.dpen, 0) AS deuda
                      FROM credit_portfolio.credit_accounts a
                      LEFT JOIN ajuste j ON j.credit_account_id = a.credit_account_id),
                exigible AS (
                    -- Delinquency is measured on principal, not on installment statuses: what is overdue never exceeds
                    -- the principal still owed at D beyond what is not due yet (an installment's total carries the
                    -- plan's interest, the account accrues the real one, and a payment capped at the debt leaves the
                    -- last installments PARTIAL forever).
                    SELECT p.credit_account_id,
                           least(coalesce(sum(p.principal_amount) FILTER (WHERE p.vencida), 0),
                                 greatest(0, max(k.capital) - coalesce(sum(p.principal_amount) FILTER (WHERE NOT p.vencida), 0))) AS capital_vencido
                      FROM pendientes p
                      JOIN capital_al_dia k ON k.credit_account_id = p.credit_account_id
                     GROUP BY p.credit_account_id),
                orden AS (
                    -- Payments cover the oldest first: what is still overdue is the newest overdue installments.
                    SELECT p.credit_account_id, p.due_date, p.principal_amount,
                           sum(p.principal_amount) OVER (PARTITION BY p.credit_account_id
                                                         ORDER BY p.due_date DESC, p.principal_amount
                                                         ROWS UNBOUNDED PRECEDING) AS acumulado
                      FROM pendientes p WHERE p.vencida),
                vencidas_por_cuota AS (
                    SELECT o.credit_account_id,
                           max(x.capital_vencido) AS capital_vencido,
                           count(*)               AS cuotas,
                           min(o.due_date)        AS mas_vieja
                      FROM orden o
                      JOIN exigible x ON x.credit_account_id = o.credit_account_id
                     WHERE x.capital_vencido > 0.005
                       AND o.acumulado - o.principal_amount < x.capital_vencido
                     GROUP BY o.credit_account_id),
                pagos AS (
                    SELECT credit_account_id, -sum(delta_amount) AS pagado
                      FROM credit_portfolio.balance_events
                     WHERE event_type IN ('PAYMENT_APPLIED', 'PAYMENT_RETURNED', 'COMMISSION_NETTED', 'COMMISSION_NETTED_REVERSED',
                                          'AGREEMENT_QUITA_PARCIAL')
                       AND value_date <= :d
                     GROUP BY credit_account_id),
                obligaciones AS (
                    -- A statement-billed product owes what its statements asked (the cycle installments, scheduleId =
                    -- account) by their due dates; payments cover the oldest first. A plan a few pesos behind because
                    -- interest is paid first is not late if the statements were paid.
                    SELECT a.credit_account_id, i.due_date,
                           sum(i.total_amount) OVER (PARTITION BY a.credit_account_id ORDER BY i.due_date, i.installment_number
                                                     ROWS UNBOUNDED PRECEDING) AS exigido
                      FROM credit_portfolio.credit_accounts a
                      JOIN credit_portfolio.installments i ON i.schedule_id = a.credit_account_id
                     WHERE a.product_behavior = 'REVOLVING' AND i.due_date < :d),
                vencidas_por_estado AS (
                    SELECT o.credit_account_id,
                           least(max(o.exigido) - coalesce(max(p.pagado), 0), greatest(max(k.deuda), 0)) AS capital_vencido,
                           count(*) FILTER (WHERE o.exigido > coalesce(p.pagado, 0))    AS cuotas,
                           min(o.due_date) FILTER (WHERE o.exigido > coalesce(p.pagado, 0)) AS mas_vieja
                      FROM obligaciones o
                      LEFT JOIN pagos p ON p.credit_account_id = o.credit_account_id
                      JOIN capital_al_dia k ON k.credit_account_id = o.credit_account_id
                     GROUP BY o.credit_account_id
                    HAVING least(max(o.exigido) - coalesce(max(p.pagado), 0), greatest(max(k.deuda), 0)) > 0.005),
                vencidas AS (
                    SELECT credit_account_id, capital_vencido, cuotas, mas_vieja FROM vencidas_por_cuota
                    UNION ALL
                    SELECT credit_account_id, capital_vencido, cuotas, mas_vieja FROM vencidas_por_estado),
                sin_intereses AS (
                    -- A purchase deferred interest-free (meses sin intereses) does not bear the line's ordinary
                    -- interest: its installments not yet due leave the basis. An installment past its due date and
                    -- unpaid goes back to the balance that does. Only deferrals made by the end of D count; a plan
                    -- with interest of its own keeps the line's accrual.
                    SELECT d.credit_account_id, sum(i.principal_amount) AS capital
                      FROM credit_portfolio.dispositions d
                      JOIN credit_portfolio.installments i ON i.schedule_id = d.disposition_id
                     WHERE d.deferred_at IS NOT NULL AND d.deferred_at < :fin
                       AND i.status IN ('PENDING', 'PARTIAL', 'OVERDUE') AND i.due_date >= :d
                       AND NOT EXISTS (SELECT 1 FROM credit_portfolio.installments x
                                        WHERE x.schedule_id = d.disposition_id AND x.interest_amount > 0)
                     GROUP BY d.credit_account_id),
                del_periodo AS (
                    -- The interest the plans set for the periods that end on D: what a product that charges interest
                    -- per period is charged today. The period ends on the plan's own date (original_due_date): billing
                    -- moves an installment's due date to the statement's payment due date, and two periods billed by
                    -- the same statement would otherwise be charged together on that date. The account's own plan and
                    -- its placements' plans both count; an installment a restructure replaced owes nothing. The status
                    -- is not looked at: paying the statement that billed an installment marks it paid, and its period's
                    -- interest is owed all the same.
                    -- Except a placement's installment a payoff settled before its date: a statement-billed line marks a
                    -- plan's unbilled installments paid only when the whole debt is paid, which pays their principal and
                    -- not the interest of periods still to run. Counting them charged a line paid off in December the
                    -- old placements' interest again in January, once a new placement gave it principal (D06).
                    SELECT coalesce(d.credit_account_id, i.schedule_id) AS credit_account_id, sum(i.interest_amount) AS interes,
                           -- Each installment's IVA, rounded as its client pays it: their sum is the period's IVA.
                           sum(i.tax_amount) AS iva
                      FROM credit_portfolio.installments i
                      LEFT JOIN credit_portfolio.dispositions d ON d.disposition_id = i.schedule_id
                      LEFT JOIN credit_portfolio.credit_accounts c ON c.credit_account_id = d.credit_account_id
                     WHERE coalesce(i.original_due_date, i.due_date) = :d AND i.status NOT IN ('REPLACED', 'CANCELLED') AND i.interest_amount > 0
                       AND (d.disposition_id IS NULL OR d.created_at < :fin)
                       AND NOT coalesce(c.product_behavior = 'REVOLVING' AND i.billed_cycle IS NULL AND i.status = 'PAID'
                                        AND i.paid_on < coalesce(i.original_due_date, i.due_date), false)
                     GROUP BY 1)
                INSERT INTO credit_portfolio.account_day_snapshots (""" + COLUMNS + """
                )
                SELECT :d, a.credit_account_id, a.status,
                       a.principal_balance        - coalesce(j.dp, 0),
                       a.accrued_interest_balance - coalesce(j.di, 0),
                       a.iva_balance              - coalesce(j.diva, 0),
                       a.penalty_balance          - coalesce(j.dpen, 0),
                       (a.principal_balance - coalesce(j.dp, 0)) + (a.accrued_interest_balance - coalesce(j.di, 0))
                         + (a.iva_balance - coalesce(j.diva, 0)) + (a.penalty_balance - coalesce(j.dpen, 0)),
                       coalesce(v.capital_vencido, 0), coalesce(v.cuotas, 0), v.mas_vieja,
                       CASE WHEN v.mas_vieja IS NULL THEN 0 ELSE (:d - v.mas_vieja) END,
                       a.balance_version, coalesce(j.n, 0), now(),
                       coalesce(l.total, 0),
                       coalesce(l.total, 0) - ((a.principal_balance - coalesce(j.dp, 0)) + (a.accrued_interest_balance - coalesce(j.di, 0))
                         + (a.iva_balance - coalesce(j.diva, 0)) + (a.penalty_balance - coalesce(j.dpen, 0))),
                       greatest(0, a.principal_balance - coalesce(j.dp, 0) - coalesce(m.capital, 0)),
                       -- Nothing is owed for a period of a loan with no principal left (paid off before it ran).
                       CASE WHEN a.principal_balance - coalesce(j.dp, 0) > 0 THEN coalesce(p.interes, 0) ELSE 0 END,
                       -- What the statements asked to pay on D and was not: set right after, by statement coverage.
                       0,
                       CASE WHEN a.principal_balance - coalesce(j.dp, 0) > 0 THEN coalesce(p.iva, 0) ELSE 0 END
                  FROM credit_portfolio.credit_accounts a
                  LEFT JOIN ajuste   j ON j.credit_account_id = a.credit_account_id
                  LEFT JOIN vencidas v ON v.credit_account_id = a.credit_account_id
                  LEFT JOIN libro    l ON l.credit_account_id = a.credit_account_id
                  LEFT JOIN sin_intereses m ON m.credit_account_id = a.credit_account_id
                  LEFT JOIN del_periodo   p ON p.credit_account_id = a.credit_account_id
                 WHERE a.activated_at IS NOT NULL AND a.activated_at < :fin
                   AND (a.status IN ('ACTIVE', 'SUSPENDED', 'RESTRUCTURED') OR coalesce(j.n, 0) > 0
                        -- The day an account ends (settled, closed, written off, cancelled) it is photographed once
                        -- more, with what it ends with: the last photo of a line closed after a quita said 62 days late.
                        OR (a.status IN ('SETTLED', 'CLOSED', 'WRITTEN_OFF', 'CANCELLED')
                            AND a.updated_at >= :inicio AND a.updated_at < :fin))
                ON CONFLICT (business_date, credit_account_id) DO NOTHING""",
                new MapSqlParameterSource("d", businessDate).addValue("fin", Timestamp.from(endOfDay))
                        .addValue("inicio", Timestamp.from(endOfDay.minus(1, ChronoUnit.DAYS))));
    }

    @Override
    public Summary summarize(LocalDate businessDate) {
        return jdbc.queryForObject("""
                SELECT count(*) AS cuentas, count(*) FILTER (WHERE days_delinquent > 0) AS morosas,
                       coalesce(sum(principal), 0) AS capital, coalesce(sum(overdue_principal), 0) AS vencido,
                       count(*) FILTER (WHERE abs(ledger_gap) > 0.005) AS desfases
                  FROM credit_portfolio.account_day_snapshots WHERE business_date = :d""",
                new MapSqlParameterSource("d", businessDate),
                (rs, i) -> new Summary(rs.getInt("cuentas"), rs.getInt("morosas"),
                        rs.getBigDecimal("capital"), rs.getBigDecimal("vencido"), rs.getInt("desfases")));
    }

    @Override
    public void complete(LocalDate businessDate, Summary s) {
        jdbc.update("""
                UPDATE credit_portfolio.day_snapshots
                   SET status = 'DONE', accounts = :n, delinquent_accounts = :m, principal_total = :p,
                       overdue_total = :o, ledger_gaps = :g, completed_at = now(), waiting_for = NULL, last_error = NULL
                 WHERE business_date = :d""",
                new MapSqlParameterSource("d", businessDate).addValue("n", s.accounts())
                        .addValue("m", s.delinquentAccounts()).addValue("p", s.principalTotal())
                        .addValue("o", s.overdueTotal()).addValue("g", s.ledgerGaps()));
    }

    @Override
    public void fail(LocalDate businessDate, String error) {
        jdbc.update("UPDATE credit_portfolio.day_snapshots SET status = 'FAILED', last_error = :e WHERE business_date = :d",
                new MapSqlParameterSource("d", businessDate).addValue("e", error));
    }

    @Override
    public Optional<DaySnapshot> find(LocalDate businessDate) {
        return jdbc.query("SELECT * FROM credit_portfolio.day_snapshots WHERE business_date = :d",
                new MapSqlParameterSource("d", businessDate), DAY).stream().findFirst();
    }

    @Override
    public List<AccountSnapshot> page(LocalDate businessDate, UUID after, int size) {
        return jdbc.query("""
                SELECT * FROM credit_portfolio.account_day_snapshots
                 WHERE business_date = :d AND (CAST(:after AS uuid) IS NULL OR credit_account_id > CAST(:after AS uuid))
                 ORDER BY credit_account_id LIMIT :n""",
                new MapSqlParameterSource("d", businessDate).addValue("after", after).addValue("n", size), ACCOUNT);
    }

    @Override
    public List<AccountSnapshot> lookup(LocalDate businessDate, Collection<UUID> creditAccountIds) {
        if (creditAccountIds.isEmpty()) return List.of();
        return jdbc.query("""
                SELECT * FROM credit_portfolio.account_day_snapshots
                 WHERE business_date = :d AND credit_account_id IN (:ids) ORDER BY credit_account_id""",
                new MapSqlParameterSource("d", businessDate).addValue("ids", creditAccountIds), ACCOUNT);
    }

    @Override
    public Optional<AccountSnapshot> find(LocalDate businessDate, UUID creditAccountId) {
        return jdbc.query("""
                SELECT * FROM credit_portfolio.account_day_snapshots WHERE business_date = :d AND credit_account_id = :a""",
                new MapSqlParameterSource("d", businessDate).addValue("a", creditAccountId), ACCOUNT).stream().findFirst();
    }

    @Override
    public List<AccountSnapshot> history(UUID creditAccountId, LocalDate from, LocalDate to) {
        return jdbc.query("""
                SELECT * FROM credit_portfolio.account_day_snapshots
                 WHERE credit_account_id = :a AND business_date BETWEEN :desde AND :hasta ORDER BY business_date""",
                new MapSqlParameterSource("a", creditAccountId).addValue("desde", from).addValue("hasta", to), ACCOUNT);
    }
}

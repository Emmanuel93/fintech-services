package com.fintech.charges.application.port.out;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * El cierre de cargos repartido por cuenta: una unidad por cuenta y día.
 *
 * <p>Estados:
 * <ul>
 *   <li>{@code PENDING} → la toma un pod ({@code CLAIMED}) → {@code DONE} o {@code FAILED};</li>
 *   <li>{@code FAILED} vuelve a {@code PENDING} mientras tenga intentos; agotados, {@code QUARANTINED}:
 *       el día cierra sin ella y queda escrito por qué;</li>
 *   <li>{@code HELD}: la cuenta tiene un día anterior sin cerrar. Su interés de hoy espera al de ayer.</li>
 * </ul>
 *
 * <p>Todas las transiciones son condicionales (CAS): un mensaje repetido, un pod muerto o dos monitores
 * a la vez no devengan dos veces ni pierden una cuenta.
 */
public interface AccrualUnitStore {

    /**
     * Creates the day's units in one {@code INSERT … SELECT}: every active schedule that must accrue
     * ordinary or moratorium interest that day, for accounts that existed when the day ended. Each unit
     * freezes its basis from the portfolio's day snapshot ({@code charges.account_day_snapshots}); the
     * moratorium is decided there too (overdue principal and oldest due date plus the grace period), not
     * from the live flag. Accounts with an earlier unclosed day are born {@code HELD}; a schedule the
     * portfolio did not photograph is born {@code QUARANTINED}: somebody has to look at it. Idempotent.
     *
     * @param endOfDay end of the business day: an account activated later does not accrue that day
     * @return how many units were created
     */
    int plan(LocalDate businessDate, Instant endOfDay);

    /** One unit as the operation sees it: status, attempts, the basis it accrued on and what it accrued. */
    Optional<UnitDetail> detail(LocalDate businessDate, UUID creditAccountId);

    /** Toma la unidad si sigue {@code PENDING}. Vacío si otro la tomó o ya terminó. */
    Optional<Claimed> claim(LocalDate businessDate, UUID creditAccountId, Duration lease);

    /**
     * La cuenta devengó. Va en la misma transacción que sus cargos: si el token ya no es el vigente
     * —otro pod la retomó—, no cambia nada y quien llama deshace su devengo.
     *
     * @return si este token la cerró
     */
    boolean complete(LocalDate businessDate, UUID creditAccountId, long fencingToken, AccountTotals totals);

    void fail(LocalDate businessDate, UUID creditAccountId, long fencingToken, String error);

    /** Devuelve a la cola lo que un pod dejó tomado y se le venció. */
    int reap(LocalDate businessDate);

    /** Devuelve a la cola las fallidas con intentos, después de {@code backoff}. */
    int requeueFailed(LocalDate businessDate, int maxAttempts, Duration backoff);

    /** Las fallidas sin intentos pasan a cuarentena. Devuelve las que entraron en esta llamada. */
    List<Trouble> quarantineExhausted(LocalDate businessDate, int maxAttempts);

    /** Suelta las retenidas cuyo día anterior ya cerró. De cada cuenta, sólo el siguiente día. */
    int releaseHeld();

    /** Pendientes que nunca salieron al tópico o salieron antes de {@code staleBefore}, por llave. */
    List<UUID> dispatchable(LocalDate businessDate, Instant staleBefore, UUID after, int limit);

    void markDispatched(LocalDate businessDate, Collection<UUID> creditAccountIds, Instant at);

    Counts counts(LocalDate businessDate);

    /**
     * Las cuentas que quedaron fuera del lote del día: todas menos las que devengaron a tiempo. Una que se
     * recupera después sigue fuera —su devengo tardío se avisa aparte—, así que la lista no cambia al
     * volver a avisar el día y las cifras de control siguen cuadrando.
     */
    List<UUID> heldAccounts(LocalDate businessDate);

    /** Días con unidades que todavía pueden moverse: pendientes, tomadas o fallidas con intentos. */
    List<LocalDate> openDays();

    /** Cuentas que devengaron después de avisado su día y cuyo aviso no ha salido. */
    List<LateUnit> lateUnannounced(int limit);

    void lateAnnounced(LocalDate businessDate, UUID creditAccountId);

    /** Reintento manual: de cuarentena a la cola, con sus intentos en cero. {@code null} = todas las del día. */
    int retryQuarantined(LocalDate businessDate, UUID creditAccountIdOrNull);

    /** Las cuentas en problemas, de todos los días: en cuarentena o retenidas. */
    List<Trouble> inTrouble(int limit);

    TroubleCounts troubleCounts();

    record Claimed(UUID scheduleId, long fencingToken, Basis basis) {}

    /**
     * What the unit accrues on, frozen at planning time from the portfolio's snapshot.
     *
     * @param principal        ordinary-interest basis: outstanding principal at the end of D
     * @param overduePrincipal moratorium basis: principal of the installments overdue at the end of D
     * @param oldestDueDate    oldest overdue installment; the moratorium runs from it plus the grace period
     */
    /**
     * @param scheduledInterest the interest the plans set for the periods falling due that day: what an account that
     *                          accrues per period is charged (null when the snapshot did not carry it)
     * @param scheduledTax      the IVA of those installments, each rounded as its client pays it (null when the
     *                          snapshot did not carry it: then 16 % of the interest)
     */
    record Basis(BigDecimal principal, BigDecimal overduePrincipal, LocalDate oldestDueDate, BigDecimal scheduledInterest,
                 BigDecimal scheduledTax) {

        public Basis(BigDecimal principal, BigDecimal overduePrincipal, LocalDate oldestDueDate,
                     BigDecimal scheduledInterest) {
            this(principal, overduePrincipal, oldestDueDate, scheduledInterest, null);
        }

        public Basis(BigDecimal principal, BigDecimal overduePrincipal, LocalDate oldestDueDate) {
            this(principal, overduePrincipal, oldestDueDate, null, null);
        }

        /** Whether the moratorium runs on {@code day} given the schedule's grace period. */
        public boolean moratoriumRunsOn(LocalDate day, int graceDays) {
            return overduePrincipal != null && overduePrincipal.signum() > 0 && oldestDueDate != null
                   && !day.isBefore(oldestDueDate.plusDays(graceDays));
        }
    }

    record UnitDetail(LocalDate businessDate, UUID creditAccountId, UUID scheduleId, String status, int attempts,
                      String reason, Basis basis, Long basisVersion, AccountTotals totals, boolean late,
                      Instant dispatchedAt, Instant completedAt) {}

    /** Lo que devengó una cuenta en el cierre de un día: sus cifras de control. */
    record AccountTotals(int ordinaryCharges, BigDecimal ordinaryAmount,
                         int moratoriumCharges, BigDecimal moratoriumAmount) {

        public static final AccountTotals NONE = new AccountTotals(0, BigDecimal.ZERO, 0, BigDecimal.ZERO);
    }

    record Counts(int pending, int claimed, int done, int failed, int quarantined, int held) {
        /** Lo que todavía puede cambiar sin que nadie intervenga. */
        public int open()  { return pending + claimed + failed; }
        public int total() { return pending + claimed + done + failed + quarantined + held; }
    }

    record Trouble(LocalDate businessDate, UUID creditAccountId, String status, int attempts, String reason) {}

    record LateUnit(LocalDate businessDate, UUID creditAccountId, AccountTotals totals) {}

    record TroubleCounts(int quarantined, int held) {}
}

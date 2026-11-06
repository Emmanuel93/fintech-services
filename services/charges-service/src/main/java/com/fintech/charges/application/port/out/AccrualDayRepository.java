package com.fintech.charges.application.port.out;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * La bitácora del cierre de cargos: un renglón por día. Es lo que hace que los días se cierren en
 * orden y sin huecos, que un aviso repetido no devengue dos veces y que se pueda decir, para cada
 * día, por qué no ha cerrado.
 */
public interface AccrualDayRepository {

    enum Claim {
        /** Este proceso cierra el día. */
        CLAIMED,
        /** Ya se cerró. */
        ALREADY_DONE,
        /** Otro proceso lo está cerrando ahora. */
        IN_PROGRESS
    }

    /**
     * Toma el día. Lo consigue si nadie lo ha tomado, si está esperando una compuerta, si el intento
     * anterior falló o si quien lo tomó lleva más de {@code staleAfter} sin terminar.
     */
    Claim claim(LocalDate businessDate, String triggeredBy, Duration staleAfter);

    /** El día no puede arrancar todavía; queda escrito por qué. */
    void waiting(LocalDate businessDate, String triggeredBy, String reason);

    /** El día quedó repartido en unidades por cuenta. */
    void planned(LocalDate businessDate, int units);

    /**
     * Cierra el día si sigue corriendo. Lo intentan el job y el monitor, quizá a la vez desde dos pods:
     * sólo uno lo consigue, y sólo ése avisa.
     *
     * @return si esta llamada lo cerró
     */
    boolean complete(LocalDate businessDate, Tallies tallies, ControlTotals totals, int heldUnits);

    /** Anota por qué un día que corre no ha podido cerrar, sin cambiar su estado. */
    void note(LocalDate businessDate, String reason);

    /** Días repartidos que siguen corriendo, del más viejo al más nuevo. */
    List<LocalDate> running();

    void fail(LocalDate businessDate, String error);

    void announced(LocalDate businessDate);

    void republished(LocalDate businessDate);

    Optional<AccrualDay> find(LocalDate businessDate);

    /** El último día cerrado. Vacío si nunca se ha cerrado ninguno. */
    Optional<LocalDate> lastDone();

    /** Whether any day strictly before {@code businessDate} is closed: when none is, that day is the first one. */
    boolean anyDoneBefore(LocalDate businessDate);

    /** Whether any account had an accrual schedule by the end of that business day (instant given). */
    boolean anyScheduleBornBefore(Instant endOfDay);

    /** Días cerrados que cartera no ha confirmado, del más viejo al más nuevo. */
    List<AccrualDay> unconfirmed(LocalDate since);

    /** Cartera cerró ese día ({@code credit-portfolio.day-closed}). Idempotente. */
    void recordPortfolioClose(LocalDate businessDate, LocalDate delinquencyAsOf);

    boolean portfolioClosed(LocalDate businessDate);

    /** Closing terminó el corte de ese día ({@code closing.cutoff-run-completed}), sellado o con fallas. */
    void recordCutoffCompleted(LocalDate businessDate, String status);

    boolean cutoffCompleted(LocalDate businessDate);

    /** Cuántos cargos de interés generó el devengo de ese día, y por cuánto. Sale de la bitácora de cargos. */
    ControlTotals totalsFor(LocalDate businessDate);

    /** Los cargos que el devengo de ese día publicó —interés, moratorio y su IVA—, para reenviarlos. */
    List<AccruedCharge> accruedCharges(LocalDate businessDate);

    /** The charges one account produced in that day's close, with the basis each one was computed on. */
    List<AccountCharge> accountCharges(LocalDate businessDate, UUID creditAccountId);

    record AccountCharge(UUID chargeId, String chargeType, String status, BigDecimal basis, BigDecimal rate,
                         BigDecimal amount, LocalDate accrualDate, Instant createdAt) {}

    /**
     * Las cuentas del día: las que devengaron, las que quedaron sin devengar (cuarentena o retenidas) y
     * cuántas cobraron ordinario y moratorio.
     */
    record Tallies(int ordinarySchedules, int ordinaryErrors, int moratoriumSchedules, int moratoriumErrors) {}

    /**
     * Las cifras de control del día: con ellas cartera comprueba que aplicó todo el interés antes de
     * seguir. Cuentan cada cargo que salió, aunque cartera lo haya rechazado después.
     */
    record ControlTotals(int ordinaryCharges, BigDecimal ordinaryAmount,
                         int moratoriumCharges, BigDecimal moratoriumAmount) {

        public static final ControlTotals NONE = new ControlTotals(0, BigDecimal.ZERO, 0, BigDecimal.ZERO);
    }

    record AccruedCharge(UUID chargeId, UUID creditAccountId, String chargeType, BigDecimal amount,
                         LocalDate accrualDate) {}

    record AccrualDay(LocalDate businessDate, String status, String triggeredBy, int attempts, String waitingFor,
                      Tallies tallies, ControlTotals totals, String lastError, Instant startedAt,
                      Instant completedAt, Instant announcedAt, Instant republishedAt,
                      Integer plannedUnits, Integer heldUnits) {

        public boolean done() { return "DONE".equals(status); }

        public boolean running() { return "RUNNING".equals(status); }
    }
}

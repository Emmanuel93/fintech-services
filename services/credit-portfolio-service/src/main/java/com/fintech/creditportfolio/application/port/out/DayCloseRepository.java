package com.fintech.creditportfolio.application.port.out;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/**
 * La bitácora del cierre del día: un renglón por día. Es lo que hace idempotente el cierre —el aviso
 * de cargos puede llegar dos veces, o junto con la reanudación— y lo que dice, para cada día, por qué
 * no ha cerrado.
 */
public interface DayCloseRepository {

    enum Claim {
        /** Este proceso cierra el día. */
        CLAIMED,
        /** Ya se cerró: sólo se vuelve a avisar. */
        ALREADY_DONE,
        /** Otro proceso lo está cerrando ahora. */
        IN_PROGRESS
    }

    /**
     * Toma el día para cerrarlo. Lo consigue si nadie lo ha tomado, si estaba esperando una compuerta,
     * si el intento anterior falló o si quien lo tomó lleva más de {@code staleAfter} sin terminar.
     */
    Claim claim(LocalDate businessDate, LocalDate delinquencyAsOf, String triggeredBy, Duration staleAfter);

    /** El día no puede cerrarse todavía; queda escrito por qué. No pisa un día que corre o ya cerró. */
    void waiting(LocalDate businessDate, LocalDate delinquencyAsOf, String triggeredBy, String reason);

    void complete(LocalDate businessDate, int accounts, int errors);

    void fail(LocalDate businessDate, String error);

    Optional<DayClose> find(LocalDate businessDate);

    /** ¿Hay algún día anterior a éste en la bitácora? Sin ninguno, éste es el primero. */
    boolean anyBefore(LocalDate businessDate);

    /** Lo que cargos dijo que devengó ese día. Idempotente: el aviso puede repetirse. */
    void recordChargesAccrued(ChargesDay day);

    Optional<ChargesDay> chargesDay(LocalDate businessDate);

    /** Días que cargos ya avisó y cartera no ha cerrado, del más viejo al más nuevo. */
    List<LocalDate> announcedNotClosed();

    /**
     * Cuántos cargos de interés del lote de ese día tiene cartera, aplicados más rechazados, y por cuánto,
     * sin las cuentas que cargos dejó fuera del lote. Es la otra mitad de las cifras de control.
     */
    ChargesTotals appliedInterest(LocalDate businessDate, Collection<UUID> outsideBatch);

    /** Lo mismo, de una sola cuenta: con eso se suelta una cuenta que devengó tarde. */
    ChargesTotals accountInterest(LocalDate businessDate, UUID creditAccountId);

    record DayClose(LocalDate businessDate, LocalDate delinquencyAsOf, String status, String triggeredBy,
                    Integer accounts, Integer errors, String lastError, String waitingFor, int attempts,
                    Instant startedAt, Instant completedAt) {

        public boolean done() { return "DONE".equals(status); }
    }

    /** Las cifras de control de un día: cargos de interés ordinario y moratorio, cuántos y por cuánto. */
    record ChargesTotals(int ordinaryCharges, BigDecimal ordinaryAmount,
                         int moratoriumCharges, BigDecimal moratoriumAmount) {

        /** Mismo número de cargos y mismos importes (a centavos de escala 4). */
        public boolean matches(ChargesTotals o) {
            return ordinaryCharges == o.ordinaryCharges && moratoriumCharges == o.moratoriumCharges
                   && ordinaryAmount.compareTo(o.ordinaryAmount) == 0
                   && moratoriumAmount.compareTo(o.moratoriumAmount) == 0;
        }
    }

    /**
     * Lo que cargos avisó de un día. {@code heldAccounts}: las cuentas que no devengaron ese día —en
     * cuarentena o retenidas—; no cuentan en las cifras y closing retiene su corte.
     */
    record ChargesDay(LocalDate businessDate, ChargesTotals totals, Instant receivedAt, List<UUID> heldAccounts) {

        public ChargesDay(LocalDate businessDate, ChargesTotals totals, Instant receivedAt) {
            this(businessDate, totals, receivedAt, List.of());
        }
    }
}

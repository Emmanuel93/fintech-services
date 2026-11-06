package com.fintech.creditportfolio.infrastructure.job;

import com.fintech.creditportfolio.application.port.out.CreditPortfolioEventPublisher;
import com.fintech.creditportfolio.application.port.out.DayCloseRepository;
import com.fintech.creditportfolio.application.port.out.DayCloseRepository.ChargesTotals;
import com.fintech.creditportfolio.application.port.out.DayCloseRepository.Claim;
import com.fintech.creditportfolio.application.port.out.DayCloseRepository.DayClose;
import com.fintech.creditportfolio.application.port.out.DaySnapshotRepository;
import com.fintech.creditportfolio.application.port.out.DaySnapshotRepository.DaySnapshot;
import com.fintech.creditportfolio.domain.event.DayClosedEvent;
import com.fintech.shared.kafka.ConsumerCatchUp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import com.fintech.shared.time.BusinessClock;

/**
 * La parte de cartera en el cierre del día D. Arranca cuando cargos avisa que devengó D
 * ({@code charges.day-accrued}, pasadas las 00:05 de D+1) y termina avisando
 * {@code credit-portfolio.day-closed}, que es lo que espera closing para cortar D.
 *
 * <h2>Las compuertas</h2>
 *
 * Se revisan siempre, venga el disparo del aviso, de la reanudación o del soporte de pruebas:
 *
 * <ol>
 *   <li><b>Cargos avisó que devengó D</b>, con sus cifras de control.</li>
 *   <li><b>D-1 está cerrado aquí.</b> Los días se cierran en orden.</li>
 *   <li><b>Cartera ya consumió todo lo que cargos publicó.</b> El aviso viaja por un tópico y los
 *       intereses por otro: el aviso puede llegar antes que el último interés.</li>
 *   <li><b>Las cifras cuadran</b>, sin las cuentas que cargos dejó fuera del lote: los intereses ordinarios y moratorios del lote del cierre de D
 *       ({@code day_close_date}) aplicados, más los rechazados, son los que cargos dijo, en número y en
 *       importe. El lote y no la fecha valor: un interés del mismo día sembrado a mano no es del cierre. Si un mensaje se descartó en el
 *       camino, aquí se nota, y el día espera a que cargos lo reenvíe.</li>
 * </ol>
 *
 * <p>Si una no abre, el día queda {@code WAITING} con el motivo escrito, y lo vuelve a intentar la
 * reanudación cada 15 minutos. Ningún día cierra sin su interés.
 *
 * <h2>Lo que hace al pasar</h2>
 *
 * <ol>
 *   <li><b>Mora al D+1.</b> La mora corre desde las 00:00: la cuota que vencía el 10 y no se pagó
 *       amanece el 11 con un día de atraso.</li>
 *   <li>Cuotas próximas y cuotas vencidas, también al D+1.</li>
 *   <li>Avisa, después de que el broker confirmó todo lo que cartera publicó antes —saldos y mora—:
 *       closing toma su foto de offsets al recibir el aviso.</li>
 * </ol>
 *
 * <p>Un día se cierra una vez. Si el aviso de cargos llega dos veces, la segunda sólo vuelve a avisar a
 * closing; nunca recalcula la mora.
 */
@Component
public class DayCloseRunner {

    private static final Logger log = LoggerFactory.getLogger(DayCloseRunner.class);

    /** Lo que cargos publica y cartera tiene que haber consumido antes de cerrar el día. */
    static final List<String> CHARGES_TOPICS = List.of("charges.charge-applied", "charges.charge-reversed");

    public enum Outcome { CLOSED, ALREADY_CLOSED, WAITING, IN_PROGRESS }

    public record Result(LocalDate businessDate, Outcome outcome, String detail) {
        public boolean closed() { return outcome == Outcome.CLOSED || outcome == Outcome.ALREADY_CLOSED; }
    }

    private final DayCloseRepository dayCloses;
    private final DaySnapshotRepository snapshots;
    private final DelinquencyCalculationJob delinquency;
    private final UpcomingInstallmentJob upcoming;
    private final InstallmentDueJob due;
    private final CreditPortfolioEventPublisher publisher;
    private final ConsumerCatchUp catchUp;
    private final Duration staleAfter;
    private final Duration gateWait;
    private final Duration gatePoll;
    private final String consumerGroup;

    @Autowired
    public DayCloseRunner(DayCloseRepository dayCloses, DaySnapshotRepository snapshots,
                          DelinquencyCalculationJob delinquency,
                          UpcomingInstallmentJob upcoming, InstallmentDueJob due,
                          CreditPortfolioEventPublisher publisher, ConsumerCatchUp catchUp,
                          @Value("${fintech.day-close.stale-after:PT2H}") Duration staleAfter,
                          @Value("${fintech.day-close.gate-wait:PT10M}") Duration gateWait,
                          @Value("${fintech.day-close.gate-poll:PT2S}") Duration gatePoll,
                          @Value("${spring.kafka.consumer.group-id:credit-portfolio-service}") String consumerGroup) {
        this.dayCloses     = dayCloses;
        this.snapshots     = snapshots;
        this.delinquency   = delinquency;
        this.upcoming      = upcoming;
        this.due           = due;
        this.publisher     = publisher;
        this.catchUp       = catchUp;
        this.staleAfter    = staleAfter;
        this.gateWait      = gateWait;
        this.gatePoll      = gatePoll;
        this.consumerGroup = consumerGroup;
    }

    /**
     * Cierra cada día que cargos ya avisó y cartera no ha cerrado, del más viejo al más nuevo, y se
     * detiene en el primero que no pueda.
     */
    public List<Result> advance(String trigger) {
        List<Result> hechos = new ArrayList<>();
        for (LocalDate d : dayCloses.announcedNotClosed()) {
            Result r = close(d, trigger);
            hechos.add(r);
            if (!r.closed()) break;
        }
        return hechos;
    }

    public Result close(LocalDate businessDate, String triggeredBy) {
        LocalDate asOf = businessDate.plusDays(1);
        if (dayCloses.find(businessDate).filter(DayClose::done).isPresent()) {
            return reannounce(businessDate, triggeredBy);
        }

        Optional<String> espera = gate(businessDate);
        if (espera.isPresent()) {
            dayCloses.waiting(businessDate, asOf, triggeredBy, espera.get());
            log.warn("Cierre del {} en espera ({}): {}", businessDate, triggeredBy, espera.get());
            return new Result(businessDate, Outcome.WAITING, espera.get());
        }

        Claim claim = dayCloses.claim(businessDate, asOf, triggeredBy, staleAfter);
        if (claim == Claim.IN_PROGRESS) {
            log.info("El cierre del día {} ya está corriendo; aviso de {} ignorado", businessDate, triggeredBy);
            return new Result(businessDate, Outcome.IN_PROGRESS, "otro proceso lo está cerrando");
        }
        if (claim == Claim.ALREADY_DONE) {
            return reannounce(businessDate, triggeredBy);
        }

        long inicio = System.nanoTime();
        DelinquencyCalculationJob.Tally mora;
        try {
            mora = delinquency.runFor(asOf);
            upcoming.runFor(asOf);
            due.runFor(asOf);
            dayCloses.complete(businessDate, mora.accounts(), mora.errors());
        } catch (RuntimeException e) {
            dayCloses.fail(businessDate, e.getClass().getSimpleName() + ": " + e.getMessage());
            throw e;
        }
        publisher.publishDayClosed(new DayClosedEvent(businessDate, asOf, mora.accounts(), mora.errors(),
                BusinessClock.current().now(), heldAccounts(businessDate)));
        log.info("Día {} cerrado en cartera ({}): mora al {} en {} cuentas, {} errores, {} ms",
                businessDate, triggeredBy, asOf, mora.accounts(), mora.errors(),
                (System.nanoTime() - inicio) / 1_000_000);
        return new Result(businessDate, Outcome.CLOSED, "mora al " + asOf + " en " + mora.accounts() + " cuentas");
    }

    /** Vacío si el día puede cerrarse; si no, por qué. */
    private Optional<String> gate(LocalDate d) {
        Optional<DayCloseRepository.ChargesDay> cargos = dayCloses.chargesDay(d);
        if (cargos.isEmpty()) {
            return Optional.of("cargos no ha avisado que devengó el " + d);
        }
        LocalDate anterior = d.minusDays(1);
        if (dayCloses.anyBefore(d) && dayCloses.find(anterior).filter(DayClose::done).isEmpty()) {
            return Optional.of("falta cerrar el " + anterior + ": los días se cierran en orden");
        }
        // Charges accrues on top of the day snapshot; without one, whatever it announced did not come from it.
        if (snapshots.find(d).filter(DaySnapshot::done).isEmpty()) {
            return Optional.of("cartera no tomó la foto del " + d + ": el devengo tenía que partir de ella");
        }
        var consumo = catchUp.await(consumerGroup, CHARGES_TOPICS, gateWait, gatePoll);
        if (!consumo.caughtUp()) {
            return Optional.of("faltan " + consumo.pending() + " cargos de charges por consumir");
        }
        ChargesTotals avisado = cargos.get().totals();
        ChargesTotals aplicado = dayCloses.appliedInterest(d, cargos.get().heldAccounts());
        if (!aplicado.matches(avisado)) {
            return Optional.of(String.format(
                    "los intereses del %s no cuadran: cargos avisó %d ordinarios por %s y %d moratorios por %s;"
                    + " cartera tiene %d por %s y %d por %s",
                    d, avisado.ordinaryCharges(), pesos(avisado.ordinaryAmount()),
                    avisado.moratoriumCharges(), pesos(avisado.moratoriumAmount()),
                    aplicado.ordinaryCharges(), pesos(aplicado.ordinaryAmount()),
                    aplicado.moratoriumCharges(), pesos(aplicado.moratoriumAmount())));
        }
        return Optional.empty();
    }

    private Result reannounce(LocalDate businessDate, String triggeredBy) {
        var hecho = dayCloses.find(businessDate).orElseThrow();
        publisher.publishDayClosed(new DayClosedEvent(businessDate, hecho.delinquencyAsOf(),
                nz(hecho.accounts()), nz(hecho.errors()), hecho.completedAt(), heldAccounts(businessDate)));
        log.info("El día {} ya estaba cerrado; se vuelve a avisar a closing ({})", businessDate, triggeredBy);
        return new Result(businessDate, Outcome.ALREADY_CLOSED, "ya estaba cerrado; se volvió a avisar");
    }

    /** Las cuentas que cargos dejó fuera del lote: closing retiene su corte. */
    private List<java.util.UUID> heldAccounts(LocalDate businessDate) {
        return dayCloses.chargesDay(businessDate).map(DayCloseRepository.ChargesDay::heldAccounts).orElse(List.of());
    }

    private static int nz(Integer v) { return v == null ? 0 : v; }

    private static String pesos(java.math.BigDecimal v) {
        return v.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }
}

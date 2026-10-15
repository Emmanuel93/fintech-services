package com.fintech.banking.traceability;

import com.fintech.banking.application.port.out.MovementTraceRepository;
import com.fintech.banking.application.service.MovementTraceService;
import com.fintech.banking.domain.MovementTrace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La cadena del dinero, de punta a punta y al revés.
 *
 * <p>Los eslabones existían —cada uno guarda el id del anterior— pero recorrerla exigía saltar de
 * cartera a disbursement, de ahí al conector, de ahí al estado de cuenta y de ahí al mayor. Nadie la
 * recorre así en una investigación real: se pregunta por chat.
 */
class MoneyTrailTest {

    private final List<MovementTrace> traces = new ArrayList<>();
    private MovementTraceService servicio;

    private final UUID payment    = UUID.randomUUID();
    private final UUID credit = UUID.randomUUID();
    private final UUID account  = UUID.randomUUID();
    private final UUID line   = UUID.randomUUID();
    private static final String CLAVE = "CR-2026-000123";
    private static final LocalDate DIA = LocalDate.of(2026, 6, 15);

    @BeforeEach
    void init() {
        traces.clear();
        MovementTraceRepository repo = new MovementTraceRepository() {
            @Override public MovementTrace save(MovementTrace t) {
                traces.removeIf(x -> x.getId().equals(t.getId())); traces.add(t); return t;
            }
            @Override public Optional<MovementTrace> findByPayoutId(UUID id) {
                return traces.stream().filter(t -> id.equals(t.getPayoutId())).findFirst();
            }
            @Override public Optional<MovementTrace> findByTrackingKey(String k) {
                return traces.stream().filter(t -> k.equals(t.getTrackingKey())).findFirst();
            }
            @Override public Optional<MovementTrace> findByLineId(UUID id) {
                return traces.stream().filter(t -> id.equals(t.getLineId())).findFirst();
            }
            @Override public List<MovementTrace> findByCreditAccountId(UUID id) {
                return traces.stream().filter(t -> id.equals(t.getCreditAccountId())).toList();
            }
            @Override public List<MovementTrace> findByVoucherRef(String ref) {
                return traces.stream().filter(t -> ref.equals(t.getVoucherRef())).toList();
            }
            @Override public List<MovementTrace> findIncomplete(int limite) {
                return traces.stream().filter(t -> !t.isComplete()).limit(limite).toList();
            }
        };
        servicio = new MovementTraceService(repo);
    }

    private void fullPath() {
        servicio.onRequest(payment, "credit-portfolio", credit.toString(), credit,
                new BigDecimal("20000.00"), "evt-1");
        servicio.onRoute(payment, account, "evt-2");
        servicio.onDispatch(payment, CLAVE, DIA, "evt-3");
        servicio.onReconcile(CLAVE, line, "evt-4");
        servicio.onPost(payment, "POL-2026-06-0042", "evt-5");
    }

    @Test
    @DisplayName("las CUATRO preguntas se contestan con una consulta cada una")
    void lasCuatroPreguntas() {
        fullPath();

        // 1 · este crédito, ¿por dónde salió su dinero?
        assertThat(servicio.byCreditAccount(credit)).hasSize(1);
        // 2 · esta clave, ¿de qué crédito era?
        assertThat(servicio.byTrackingKey(CLAVE))
                .get().extracting(MovementTrace::getCreditAccountId).isEqualTo(credit);
        // 3 · este movimiento del banco, ¿a qué corresponde?
        assertThat(servicio.byStatementLine(line))
                .get().extracting(MovementTrace::getPayoutId).isEqualTo(payment);
        // 4 · esta póliza, ¿qué dinero real la respalda?
        assertThat(servicio.byJournalEntry("POL-2026-06-0042")).hasSize(1);
    }

    @Test
    @DisplayName("recorrido el camino entero, la traza está completa")
    void completaDePuntaAPunta() {
        fullPath();

        MovementTrace t = traces.get(0);
        assertThat(t.isComplete()).isTrue();
        assertThat(t.missingLink()).isNull();
        assertThat(t.getStatus()).isEqualTo("RECONCILED");
    }

    @Test
    @DisplayName("🔑 una traza a medias señala EN QUÉ ESLABÓN se detuvo")
    void señalaDondeSeDetuvo() {
        servicio.onRequest(payment, "credit-portfolio", credit.toString(), credit,
                new BigDecimal("20000.00"), "evt-1");
        servicio.onRoute(payment, account, "evt-2");
        servicio.onDispatch(payment, CLAVE, DIA, "evt-3");

        // Saber que está incompleta no ayuda; saber que le falta conciliar dice a quién preguntar.
        MovementTrace t = traces.get(0);
        assertThat(t.isComplete()).isFalse();
        assertThat(t.missingLink()).isEqualTo("SIN_CONCILIAR");
        assertThat(servicio.incomplete(10)).hasSize(1);
    }

    @Test
    @DisplayName("un pago que se quedó sin rutear se distingue de uno sin despachar")
    void distingueLosEslabones() {
        servicio.onRequest(payment, "credit-portfolio", credit.toString(), credit,
                new BigDecimal("20000.00"), "evt-1");

        assertThat(traces.get(0).missingLink()).isEqualTo("SIN_RUTEAR");

        servicio.onRoute(payment, account, "evt-2");
        assertThat(traces.get(0).missingLink()).isEqualTo("SIN_DESPACHAR");
    }

    @Test
    @DisplayName("abrir dos veces la misma traza no la duplica")
    void aperturaIdempotente() {
        servicio.onRequest(payment, "credit-portfolio", credit.toString(), credit,
                new BigDecimal("20000.00"), "evt-1");
        servicio.onRequest(payment, "credit-portfolio", credit.toString(), credit,
                new BigDecimal("20000.00"), "evt-1");

        assertThat(traces).hasSize(1);
    }

    @Test
    @DisplayName("un hecho que llega ANTES que su solicitud no rompe nada")
    void toleraElDesorden() {
        // Kafka no garantiza orden entre topics: la conciliación puede llegar antes que el despacho
        // si el poller del banco corrió primero. No debe explotar ni inventar una traza.
        servicio.onReconcile(CLAVE, line, "evt-4");
        servicio.onRoute(payment, account, "evt-2");

        assertThat(traces).isEmpty();
    }

    @Test
    @DisplayName("una devolución queda registrada en la traza")
    void laDevolucionSeVe() {
        servicio.onRequest(payment, "credit-portfolio", credit.toString(), credit,
                new BigDecimal("20000.00"), "evt-1");
        servicio.onRoute(payment, account, "evt-2");
        servicio.onDispatch(payment, CLAVE, DIA, "evt-3");

        traces.get(0).returned("evt-9");

        assertThat(traces.get(0).getStatus()).isEqualTo("RETURNED");
    }
}

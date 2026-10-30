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
    @DisplayName("la traza que abrió el despacho se completa con el origen cuando llega quien lo sabe")
    void elOrigenSeCompletaSinPisarse() {
        // El despacho al conector no sabe de qué crédito viene: abre la traza sin origen.
        servicio.onRequest(payment, null, null, null, new BigDecimal("45000.00"), "evt-route");
        servicio.onRoute(payment, account, "evt-route");

        // disbursement.accepted sí lo sabe. Antes era un no-op «idempotente» y el pago nunca
        // aparecía al preguntar por el crédito.
        servicio.onRequest(payment, "credit-portfolio", "disp-1", credit,
                new BigDecimal("99.00"), "evt-accepted");

        MovementTrace t = traces.get(0);
        assertThat(traces).hasSize(1);
        assertThat(t.getCreditAccountId()).isEqualTo(credit);
        assertThat(t.getSourceSystem()).isEqualTo("credit-portfolio");
        // Lo que ya estaba no se pisa: el primero que lo dijo manda.
        assertThat(t.getAmount()).isEqualByComparingTo("45000.00");
        assertThat(servicio.byCreditAccount(credit)).hasSize(1);
        assertThat(servicio.byPayout(payment)).isPresent();
    }

    @Test
    @DisplayName("un despacho que llega antes que el ruteo no retrocede: la cuenta se completa y sigue DISPATCHED")
    void despachoAntesQueRuteo() {
        servicio.onRequest(payment, "credit-portfolio", "disp-1", credit, new BigDecimal("1.00"), "evt-1");
        servicio.onDispatch(payment, CLAVE, null, "evt-3");
        servicio.onRoute(payment, account, "evt-2");

        MovementTrace t = traces.get(0);
        assertThat(t.getStatus()).isEqualTo("DISPATCHED");
        assertThat(t.getBankAccountId()).isEqualTo(account);
        assertThat(t.missingLink()).isEqualTo("SIN_CONCILIAR");
    }

    @Test
    @DisplayName("un ruteo reentregado tarde no cambia la cuenta por la que el dinero ya salió")
    void ruteoTardioNoCambiaLaCuenta() {
        fullPathUntilDispatch();
        servicio.onRoute(payment, UUID.randomUUID(), "evt-2-bis");

        assertThat(traces.get(0).getBankAccountId()).isEqualTo(account);
        assertThat(traces.get(0).getStatus()).isEqualTo("DISPATCHED");
    }

    @Test
    @DisplayName("la liquidación completa la fecha de operación y marca SETTLED, sin cerrar la cadena")
    void laLiquidacionCompletaLaFecha() {
        servicio.onRequest(payment, "credit-portfolio", "disp-1", credit, new BigDecimal("1.00"), "evt-1");
        servicio.onRoute(payment, account, "evt-2");
        servicio.onDispatch(payment, CLAVE, null, "evt-3");

        servicio.onDispatch(payment, "OTRA-CLAVE", DIA, "evt-settled");
        servicio.onSettle(payment, java.time.OffsetDateTime.parse("2026-06-15T13:00:00Z"), "evt-settled");
        servicio.onSettle(payment, java.time.OffsetDateTime.parse("2026-06-16T13:00:00Z"), "evt-settled");

        MovementTrace t = traces.get(0);
        // La clave de una orden no cambia: la primera es la buena; la fecha sí se completa.
        assertThat(t.getTrackingKey()).isEqualTo(CLAVE);
        assertThat(t.getBusinessDate()).isEqualTo(DIA);
        assertThat(t.getStatus()).isEqualTo("SETTLED");
        assertThat(t.getSettledAt()).isEqualTo(java.time.OffsetDateTime.parse("2026-06-15T13:00:00Z"));
        assertThat(t.getDispatchedAt()).isNotNull();
        // Liquidado no es conciliado: sigue faltando que el banco lo reporte.
        assertThat(t.isComplete()).isFalse();
        assertThat(t.missingLink()).isEqualTo("SIN_CONCILIAR");
    }

    @Test
    @DisplayName("una liquidación que llega después de la conciliación no regresa el estado")
    void liquidacionTardiaNoRegresa() {
        fullPath();
        servicio.onSettle(payment, null, "evt-settled");

        assertThat(traces.get(0).getStatus()).isEqualTo("RECONCILED");
        assertThat(traces.get(0).getSettledAt()).isNotNull();
    }

    private void fullPathUntilDispatch() {
        servicio.onRequest(payment, "credit-portfolio", credit.toString(), credit,
                new BigDecimal("20000.00"), "evt-1");
        servicio.onRoute(payment, account, "evt-2");
        servicio.onDispatch(payment, CLAVE, DIA, "evt-3");
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

package com.fintech.banking.trazabilidad;

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
class CadenaDelDineroTest {

    private final List<MovementTrace> trazas = new ArrayList<>();
    private MovementTraceService servicio;

    private final UUID pago    = UUID.randomUUID();
    private final UUID credito = UUID.randomUUID();
    private final UUID cuenta  = UUID.randomUUID();
    private final UUID linea   = UUID.randomUUID();
    private static final String CLAVE = "CR-2026-000123";
    private static final LocalDate DIA = LocalDate.of(2026, 6, 15);

    @BeforeEach
    void init() {
        trazas.clear();
        MovementTraceRepository repo = new MovementTraceRepository() {
            @Override public MovementTrace save(MovementTrace t) {
                trazas.removeIf(x -> x.getId().equals(t.getId())); trazas.add(t); return t;
            }
            @Override public Optional<MovementTrace> findByPayoutId(UUID id) {
                return trazas.stream().filter(t -> id.equals(t.getPayoutId())).findFirst();
            }
            @Override public Optional<MovementTrace> findByTrackingKey(String k) {
                return trazas.stream().filter(t -> k.equals(t.getTrackingKey())).findFirst();
            }
            @Override public Optional<MovementTrace> findByLineId(UUID id) {
                return trazas.stream().filter(t -> id.equals(t.getLineId())).findFirst();
            }
            @Override public List<MovementTrace> findByCreditAccountId(UUID id) {
                return trazas.stream().filter(t -> id.equals(t.getCreditAccountId())).toList();
            }
            @Override public List<MovementTrace> findByVoucherRef(String ref) {
                return trazas.stream().filter(t -> ref.equals(t.getVoucherRef())).toList();
            }
            @Override public List<MovementTrace> findIncompletas(int limite) {
                return trazas.stream().filter(t -> !t.estaCompleta()).limit(limite).toList();
            }
        };
        servicio = new MovementTraceService(repo);
    }

    private void caminoCompleto() {
        servicio.alSolicitar(pago, "credit-portfolio", credito.toString(), credito,
                new BigDecimal("20000.00"), "evt-1");
        servicio.alRutear(pago, cuenta, "evt-2");
        servicio.alDespachar(pago, CLAVE, DIA, "evt-3");
        servicio.alConciliar(CLAVE, linea, "evt-4");
        servicio.alAsentar(pago, "POL-2026-06-0042", "evt-5");
    }

    @Test
    @DisplayName("las CUATRO preguntas se contestan con una consulta cada una")
    void lasCuatroPreguntas() {
        caminoCompleto();

        // 1 · este crédito, ¿por dónde salió su dinero?
        assertThat(servicio.porCredito(credito)).hasSize(1);
        // 2 · esta clave, ¿de qué crédito era?
        assertThat(servicio.porClaveDeRastreo(CLAVE))
                .get().extracting(MovementTrace::getCreditAccountId).isEqualTo(credito);
        // 3 · este movimiento del banco, ¿a qué corresponde?
        assertThat(servicio.porLineaBancaria(linea))
                .get().extracting(MovementTrace::getPayoutId).isEqualTo(pago);
        // 4 · esta póliza, ¿qué dinero real la respalda?
        assertThat(servicio.porPoliza("POL-2026-06-0042")).hasSize(1);
    }

    @Test
    @DisplayName("recorrido el camino entero, la traza está completa")
    void completaDePuntaAPunta() {
        caminoCompleto();

        MovementTrace t = trazas.get(0);
        assertThat(t.estaCompleta()).isTrue();
        assertThat(t.eslabonFaltante()).isNull();
        assertThat(t.getStatus()).isEqualTo("RECONCILED");
    }

    @Test
    @DisplayName("🔑 una traza a medias señala EN QUÉ ESLABÓN se detuvo")
    void señalaDondeSeDetuvo() {
        servicio.alSolicitar(pago, "credit-portfolio", credito.toString(), credito,
                new BigDecimal("20000.00"), "evt-1");
        servicio.alRutear(pago, cuenta, "evt-2");
        servicio.alDespachar(pago, CLAVE, DIA, "evt-3");

        // Saber que está incompleta no ayuda; saber que le falta conciliar dice a quién preguntar.
        MovementTrace t = trazas.get(0);
        assertThat(t.estaCompleta()).isFalse();
        assertThat(t.eslabonFaltante()).isEqualTo("SIN_CONCILIAR");
        assertThat(servicio.incompletas(10)).hasSize(1);
    }

    @Test
    @DisplayName("un pago que se quedó sin rutear se distingue de uno sin despachar")
    void distingueLosEslabones() {
        servicio.alSolicitar(pago, "credit-portfolio", credito.toString(), credito,
                new BigDecimal("20000.00"), "evt-1");

        assertThat(trazas.get(0).eslabonFaltante()).isEqualTo("SIN_RUTEAR");

        servicio.alRutear(pago, cuenta, "evt-2");
        assertThat(trazas.get(0).eslabonFaltante()).isEqualTo("SIN_DESPACHAR");
    }

    @Test
    @DisplayName("abrir dos veces la misma traza no la duplica")
    void aperturaIdempotente() {
        servicio.alSolicitar(pago, "credit-portfolio", credito.toString(), credito,
                new BigDecimal("20000.00"), "evt-1");
        servicio.alSolicitar(pago, "credit-portfolio", credito.toString(), credito,
                new BigDecimal("20000.00"), "evt-1");

        assertThat(trazas).hasSize(1);
    }

    @Test
    @DisplayName("un hecho que llega ANTES que su solicitud no rompe nada")
    void toleraElDesorden() {
        // Kafka no garantiza orden entre topics: la conciliación puede llegar antes que el despacho
        // si el poller del banco corrió primero. No debe explotar ni inventar una traza.
        servicio.alConciliar(CLAVE, linea, "evt-4");
        servicio.alRutear(pago, cuenta, "evt-2");

        assertThat(trazas).isEmpty();
    }

    @Test
    @DisplayName("una devolución queda registrada en la traza")
    void laDevolucionSeVe() {
        servicio.alSolicitar(pago, "credit-portfolio", credito.toString(), credito,
                new BigDecimal("20000.00"), "evt-1");
        servicio.alRutear(pago, cuenta, "evt-2");
        servicio.alDespachar(pago, CLAVE, DIA, "evt-3");

        trazas.get(0).devuelto("evt-9");

        assertThat(trazas.get(0).getStatus()).isEqualTo("RETURNED");
    }
}

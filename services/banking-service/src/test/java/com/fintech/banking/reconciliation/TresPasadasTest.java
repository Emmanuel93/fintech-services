package com.fintech.banking.conciliacion;

import com.fintech.banking.application.port.out.BankReconciliationRepository;
import com.fintech.banking.application.port.out.InternalMovementLookupPort;
import com.fintech.banking.application.port.out.InternalMovementLookupPort.MovimientoInterno;
import com.fintech.banking.application.service.BankReconciliationService;
import com.fintech.banking.domain.BankCloseSeal;
import com.fintech.banking.domain.BankMatch;
import com.fintech.banking.domain.BankStatementLine;
import com.fintech.banking.domain.SuspenseEntry;
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
 * El matching de tres pasadas.
 *
 * <p>La regla que ordena todo: <b>con dos candidatos no se elige</b>. Cruzar con «el primero»
 * cuadraría el reporte y cruzaría el pago contra otra persona — el total daría bien y dos cuentas
 * individuales estarían mal. Es el peor desenlace posible aquí, porque nadie lo busca.
 */
class TresPasadasTest {

    private static final UUID CUENTA = UUID.randomUUID();
    private static final LocalDate DIA = LocalDate.of(2026, 6, 15);

    private final List<BankStatementLine> lineas = new ArrayList<>();
    private final List<BankMatch> cruces = new ArrayList<>();
    private final List<SuspenseEntry> partidas = new ArrayList<>();
    private final List<MovimientoInterno> internos = new ArrayList<>();

    private BankReconciliationService servicio;

    private final BankReconciliationRepository repo = new BankReconciliationRepository() {
        @Override public BankStatementLine saveLine(BankStatementLine l) {
            lineas.removeIf(x -> x.getId().equals(l.getId())); lineas.add(l); return l;
        }
        @Override public Optional<BankStatementLine> findLineByExternalId(UUID c, String ext) {
            return lineas.stream().filter(l -> l.getExternalId().equals(ext)).findFirst();
        }
        @Override public List<BankStatementLine> findLinesByDate(UUID c, LocalDate d) {
            return List.copyOf(lineas);
        }
        @Override public BankMatch saveMatch(BankMatch m) { cruces.add(m); return m; }
        @Override public SuspenseEntry saveSuspense(SuspenseEntry e) { partidas.add(e); return e; }
        @Override public List<SuspenseEntry> findOpenSuspenseUpTo(UUID c, LocalDate d) {
            return partidas.stream().filter(SuspenseEntry::estaAbierta).toList();
        }
        @Override public BankCloseSeal saveSeal(BankCloseSeal s) { return s; }
        @Override public Optional<BankCloseSeal> findSeal(UUID c, LocalDate d, String p) {
            return Optional.empty();
        }
    };

    @BeforeEach
    void init() {
        lineas.clear(); cruces.clear(); partidas.clear(); internos.clear();
        InternalMovementLookupPort lookup = new InternalMovementLookupPort() {
            @Override public Optional<MovimientoInterno> porClaveDeRastreo(String clave) {
                return internos.stream()
                        .filter(m -> clave.equals(m.trackingKey())).findFirst();
            }
            @Override public List<MovimientoInterno> porImporteYFecha(BigDecimal monto, LocalDate d) {
                return internos.stream()
                        .filter(m -> m.amount().compareTo(monto) == 0 && m.businessDate().equals(d))
                        .toList();
            }
        };
        servicio = new BankReconciliationService(repo, lookup);
    }

    private void abono(String monto, String externalId, String clave) {
        servicio.ingerir(CUENTA, DIA, "CREDIT", new BigDecimal(monto), externalId, clave,
                "REF", "JUAN PEREZ", "012180000000000015");
    }

    private void interno(String monto, String clave, String ref) {
        internos.add(new MovimientoInterno("STP_ORDER", ref, new BigDecimal(monto), DIA, clave));
    }

    @Test
    @DisplayName("pasada 1 · la clave de rastreo cruza sin margen de error")
    void porClaveDeRastreo() {
        abono("5000.00", "MOV-1", "CR-ABC-123");
        interno("5000.00", "CR-ABC-123", "ORD-1");

        var r = servicio.conciliar(CUENTA, DIA);

        assertThat(r.deterministas()).isEqualTo(1);
        assertThat(cruces.get(0).getMethod()).isEqualTo(BankMatch.DETERMINISTA);
        // Sin confianza: en un cruce determinista no hay nada que estimar.
        assertThat(cruces.get(0).getConfidence()).isNull();
    }

    @Test
    @DisplayName("pasada 2 · importe y fecha con UN candidato cruza, y guarda la confianza")
    void porImporteYFecha() {
        abono("3200.50", "MOV-2", null);
        interno("3200.50", "OTRA-CLAVE", "ORD-2");

        var r = servicio.conciliar(CUENTA, DIA);

        assertThat(r.heuristicos()).isEqualTo(1);
        BankMatch m = cruces.get(0);
        assertThat(m.getMethod()).isEqualTo(BankMatch.HEURISTICO);
        // Sin confianza guardada, la heurística no se puede revisar después de aprobarla.
        assertThat(m.getConfidence()).isNotNull().isEqualByComparingTo("0.80");
    }

    @Test
    @DisplayName("🔑 con DOS candidatos NO se elige: va a la puente")
    void dosCandidatosNoSeElige() {
        abono("1000.00", "MOV-3", null);
        interno("1000.00", "C1", "ORD-A");
        interno("1000.00", "C2", "ORD-B");

        var r = servicio.conciliar(CUENTA, DIA);

        assertThat(r.aPuente()).isEqualTo(1);
        assertThat(cruces).isEmpty();
        // Y el motivo dice POR QUÉ: «no cuadró» no le sirve a quien la tiene que resolver.
        assertThat(partidas.get(0).getReason()).contains("Ambiguo").contains("2 movimientos");
    }

    @Test
    @DisplayName("clave conocida con importe DISTINTO no se cruza — es la señal más nítida")
    void claveConImporteDistinto() {
        abono("5000.00", "MOV-4", "CR-ABC-123");
        interno("4500.00", "CR-ABC-123", "ORD-1");

        var r = servicio.conciliar(CUENTA, DIA);

        // Taparlo cuadrando el reporte haría invisible justo lo que hay que mirar.
        assertThat(r.cruzados()).isZero();
        assertThat(r.aPuente()).isEqualTo(1);
    }

    @Test
    @DisplayName("pasada 3 · un cargo sin clave sale como partida con motivo útil")
    void cargoSinClave() {
        servicio.ingerir(CUENTA, DIA, "DEBIT", new BigDecimal("150.00"), "MOV-5", null,
                "COMISION", "BANCO", null);

        servicio.conciliar(CUENTA, DIA);

        assertThat(partidas).hasSize(1);
        assertThat(partidas.get(0).getReason()).contains("comisión o cargo del banco");
        assertThat(partidas.get(0).estaAbierta()).isTrue();
    }

    @Test
    @DisplayName("la ingesta es idempotente: el mismo movimiento reenviado no se duplica")
    void ingestaIdempotente() {
        abono("5000.00", "MOV-6", "CR-1");
        abono("5000.00", "MOV-6", "CR-1");

        // Los bancos reenvían: el mismo archivo llega dos veces, o el poller repite el día. Sin
        // esto, la conciliación cuadraría contra el doble.
        assertThat(lineas).hasSize(1);
    }

    @Test
    @DisplayName("un movimiento ya cruzado no se vuelve a procesar")
    void noSeReprocesa() {
        abono("5000.00", "MOV-7", "CR-7");
        interno("5000.00", "CR-7", "ORD-7");
        servicio.conciliar(CUENTA, DIA);

        var segunda = servicio.conciliar(CUENTA, DIA);

        assertThat(segunda.cruzados()).isZero();
        assertThat(cruces).hasSize(1);
    }

    @Test
    @DisplayName("un cruce heurístico SIN confianza no se puede ni construir")
    void heuristicoSinConfianza() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                BankMatch.heuristico(UUID.randomUUID(), "STP_ORDER", "ORD-1",
                        BigDecimal.TEN, null))
                .hasMessageContaining("no se puede revisar después");
    }

    @Test
    @DisplayName("un cruce manual SIN autor no se puede ni construir")
    void manualSinAutor() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                BankMatch.manual(UUID.randomUUID(), "STP_ORDER", "ORD-1", BigDecimal.TEN, "  "))
                .hasMessageContaining("no es auditable");
    }
}

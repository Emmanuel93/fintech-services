package com.fintech.closing;

import com.fintech.closing.application.port.out.AccountCloseProfileRepository;
import com.fintech.closing.application.port.out.CloseSealRepository;
import com.fintech.closing.application.port.out.ClosingEventPublisher;
import com.fintech.closing.application.port.out.CutoffScheduleRepository;
import com.fintech.closing.application.service.AccountProjectionService;
import com.fintech.closing.application.service.BusinessDayRunner;
import com.fintech.closing.domain.ClosePhase;
import com.fintech.closing.domain.CloseSeal;
import com.fintech.closing.domain.CutoffSchedule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TK-07 — el día de negocio completo, con el reloj corrido a mano.
 *
 * <p><b>El día se recibe, no se lee del reloj.</b> Es lo que permite reproducir el cierre del 15 el
 * día 20 y, sobre todo, recorrer el ciclo de vida de un crédito sin esperar meses reales.
 */
@SpringBootTest
@Testcontainers
@Import({InMemoryLockConfig.class, CierreDiarioIT.EventosCapturadosConfig.class})
class CierreDiarioIT {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("fintech").withUsername("fintech").withPassword("fintech");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.autoconfigure.exclude",
                () -> "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                    + "org.redisson.spring.starter.RedissonAutoConfigurationV2");
    }

    /** Captura lo publicado sin necesitar un broker: lo que importa aquí es QUÉ se publica. */
    @TestConfiguration
    static class EventosCapturadosConfig {
        static final List<String> VENTANAS = new ArrayList<>();
        static final List<CutoffSchedule> CORTES = new ArrayList<>();
        static final List<CloseSeal> SELLOS = new ArrayList<>();

        @Bean @Primary
        ClosingEventPublisher capturador() {
            return new ClosingEventPublisher() {
                @Override public void publishUnitWindowOpened(UUID runId, UUID cuenta, ClosePhase fase,
                                                               LocalDate fecha, String producto) {
                    VENTANAS.add(fase + "|" + cuenta + "|" + fecha);
                }
                @Override public void publishCutoffClosed(CutoffSchedule c, UUID party, String producto) {
                    CORTES.add(c);
                }
                @Override public void publishDaySealed(CloseSeal s) { SELLOS.add(s); }
            };
        }
    }

    @Autowired BusinessDayRunner runner;
    @Autowired AccountProjectionService proyeccion;
    @Autowired AccountCloseProfileRepository perfiles;
    @Autowired CutoffScheduleRepository cortes;
    @Autowired CloseSealRepository sellos;
    @Autowired JdbcTemplate jdbc;

    static final LocalDate ALTA = LocalDate.of(2026, 3, 10);

    @BeforeEach
    void limpiar() {
        jdbc.update("DELETE FROM closing.close_units");
        jdbc.update("DELETE FROM closing.close_runs");
        jdbc.update("DELETE FROM closing.close_seals");
        jdbc.update("DELETE FROM closing.cutoff_schedules");
        jdbc.update("DELETE FROM closing.account_close_profiles");
        EventosCapturadosConfig.VENTANAS.clear();
        EventosCapturadosConfig.CORTES.clear();
        EventosCapturadosConfig.SELLOS.clear();
    }

    private UUID activar(String producto, String comportamiento, String cadencia, Integer plazo,
                          BigDecimal saldo) {
        UUID id = UUID.randomUUID();
        proyeccion.onAccountActivated(id, UUID.randomUUID(), producto, comportamiento, "SUC-001",
                ALTA, cadencia, plazo, new BigDecimal("0.32"), saldo);
        return id;
    }

    @Test
    @DisplayName("un día de negocio corre sus fases en orden y las sella")
    void elDiaCorreYSella() {
        activar("PERSONAL_LOAN", "INSTALLMENT", "MONTHLY", 12, new BigDecimal("20000"));

        var r = runner.runBusinessDay(LocalDate.of(2026, 3, 11), "ALL");

        assertThat(r.fases()).containsKeys(ClosePhase.RECONCILE, ClosePhase.ACCRUAL,
                ClosePhase.DELINQUENCY, ClosePhase.CUTOFF);
        assertThat(r.todoSellado()).as("todas las fases sellaron").isTrue();
    }

    @Test
    @DisplayName("el devengo abre ventana por cuenta: el cierre NO devenga, lo pide")
    void elDevengoAbreVentana() {
        UUID a = activar("PERSONAL_LOAN", "INSTALLMENT", "MONTHLY", 12, new BigDecimal("20000"));
        UUID b = activar("MICRO_LOAN", "INSTALLMENT", "WEEKLY", 8, new BigDecimal("5000"));

        runner.runPhase(LocalDate.of(2026, 3, 11), ClosePhase.RECONCILE, "ALL");
        runner.runPhase(LocalDate.of(2026, 3, 11), ClosePhase.ACCRUAL, "ALL");

        assertThat(EventosCapturadosConfig.VENTANAS)
                .contains("ACCRUAL|" + a + "|2026-03-11", "ACCRUAL|" + b + "|2026-03-11");
    }

    @Test
    @DisplayName("una cuenta sin saldo se omite y NO bloquea el sello")
    void sinSaldoSeOmite() {
        activar("PERSONAL_LOAN", "INSTALLMENT", "MONTHLY", 12, BigDecimal.ZERO);

        var fase = runner.runPhase(LocalDate.of(2026, 3, 11), ClosePhase.RECONCILE, "ALL");
        var accrual = runner.runPhase(LocalDate.of(2026, 3, 11), ClosePhase.ACCRUAL, "ALL");

        assertThat(accrual.omitidas()).isEqualTo(1);
        assertThat(accrual.fallidas()).isZero();
        assertThat(accrual.sellada()).as("omitida no bloquea el sello").isTrue();
    }

    @Test
    @DisplayName("una cuenta liquidada sale del cierre")
    void liquidadaSaleDelCierre() {
        UUID id = activar("PERSONAL_LOAN", "INSTALLMENT", "MONTHLY", 12, new BigDecimal("20000"));
        proyeccion.onBalanceUpdated(id, BigDecimal.ZERO, BigDecimal.ZERO, "SETTLED", 10, "SUC-001");

        runner.runPhase(LocalDate.of(2026, 3, 11), ClosePhase.RECONCILE, "ALL");
        var accrual = runner.runPhase(LocalDate.of(2026, 3, 11), ClosePhase.ACCRUAL, "ALL");

        assertThat(accrual.unidades()).as("terminal no se materializa siquiera").isZero();
    }

    @Test
    @DisplayName("el corte se sella el día que toca, y sólo ese día")
    void elCorteSeSellaElDiaQueToca() {
        UUID id = activar("PERSONAL_LOAN", "INSTALLMENT", "MONTHLY", 12, new BigDecimal("20000"));
        proyeccion.onBalanceUpdated(id, new BigDecimal("20000"), new BigDecimal("20533.33"), "ACTIVE", 1, "SUC-001");

        // El día anterior al corte: no pasa nada.
        runner.runBusinessDay(LocalDate.of(2026, 4, 9), "ALL");
        assertThat(cortes.find(id, 1).orElseThrow().getStatus()).isEqualTo("SCHEDULED");

        // El día del corte: se sella.
        runner.runBusinessDay(LocalDate.of(2026, 4, 10), "ALL");
        CutoffSchedule corte = cortes.find(id, 1).orElseThrow();

        assertThat(corte.isSealed()).isTrue();
        assertThat(corte.getBalanceAtCutoff()).isEqualByComparingTo("20533.33");
        assertThat(EventosCapturadosConfig.CORTES).hasSize(1);
    }

    @Test
    @DisplayName("una tarjeta recibe pago mínimo; un préstamo a plazo NO lo inventa el cierre")
    void elPagoMinimoEsSoloDeRevolventes() {
        // El importe de la cuota sale del plan de amortización, que es de cartera. Duplicar aquí
        // esa aritmética crearía una segunda fuente de verdad sobre el mismo número.
        UUID tarjeta = activar("CREDIT_CARD", "REVOLVING", null, null, new BigDecimal("10000"));
        UUID prestamo = activar("PERSONAL_LOAN", "INSTALLMENT", "MONTHLY", 12, new BigDecimal("20000"));
        proyeccion.onBalanceUpdated(tarjeta, new BigDecimal("10000"), new BigDecimal("10000"), "ACTIVE", 1, "SUC-001");
        proyeccion.onBalanceUpdated(prestamo, new BigDecimal("20000"), new BigDecimal("20000"), "ACTIVE", 1, "SUC-001");

        runner.runBusinessDay(LocalDate.of(2026, 4, 10), "ALL");

        assertThat(cortes.find(tarjeta, 1).orElseThrow().getMinimumPayment())
                .as("5% del saldo").isEqualByComparingTo("500.00");
        assertThat(cortes.find(prestamo, 1).orElseThrow().getMinimumPayment())
                .as("el cierre no inventa el importe de la cuota").isNull();
    }

    @Test
    @DisplayName("tras sellar un corte, el ciclo siguiente queda agendado")
    void elCicloSigue() {
        UUID id = activar("CREDIT_CARD", "REVOLVING", null, null, new BigDecimal("10000"));
        proyeccion.onBalanceUpdated(id, new BigDecimal("10000"), new BigDecimal("10000"), "ACTIVE", 1, "SUC-001");

        runner.runBusinessDay(LocalDate.of(2026, 4, 10), "ALL");

        assertThat(cortes.find(id, 2)).isPresent();
        // El 10 de mayo de 2026 es DOMINGO: la política corre el corte al lunes siguiente.
        // La expectativa ingenua era el 10; el motor hizo lo correcto.
        assertThat(perfiles.findById(id).orElseThrow().getNextCutoffDate())
                .isEqualTo(LocalDate.of(2026, 5, 11));
    }

    @Test
    @DisplayName("el sello lleva cifras de control y una huella que detecta alteración")
    void elSelloLlevaCifras() {
        activar("PERSONAL_LOAN", "INSTALLMENT", "MONTHLY", 12, new BigDecimal("20000"));
        activar("MICRO_LOAN", "INSTALLMENT", "WEEKLY", 8, new BigDecimal("5000"));

        runner.runBusinessDay(LocalDate.of(2026, 3, 11), "ALL");

        CloseSeal seal = sellos.find(LocalDate.of(2026, 3, 11), ClosePhase.ACCRUAL, "ALL").orElseThrow();
        assertThat(seal.getTotalPrincipal()).isEqualByComparingTo("25000");
        assertThat(seal.getUnitCount()).isEqualTo(2);
        assertThat(seal.isIntact()).as("la huella corresponde al contenido").isTrue();
    }

    @Test
    @DisplayName("correr el mismo día dos veces no reprocesa ni re-sella")
    void elDiaEsIdempotente() {
        activar("PERSONAL_LOAN", "INSTALLMENT", "MONTHLY", 12, new BigDecimal("20000"));

        runner.runBusinessDay(LocalDate.of(2026, 3, 11), "ALL");
        int ventanasPrimera = EventosCapturadosConfig.VENTANAS.size();
        int sellosPrimera   = EventosCapturadosConfig.SELLOS.size();

        runner.runBusinessDay(LocalDate.of(2026, 3, 11), "ALL");

        assertThat(EventosCapturadosConfig.VENTANAS).hasSize(ventanasPrimera);
        assertThat(EventosCapturadosConfig.SELLOS).hasSize(sellosPrimera);
    }

    @Test
    @DisplayName("correr treinta días seguidos abre treinta ventanas de devengo, una por día")
    void treintaDiasTreintaVentanas() {
        // Es el requisito literal: cada devengamiento tiene que haber sido aplicado por un cierre.
        UUID id = activar("PERSONAL_LOAN", "INSTALLMENT", "MONTHLY", 12, new BigDecimal("20000"));
        proyeccion.onBalanceUpdated(id, new BigDecimal("20000"), new BigDecimal("20000"), "ACTIVE", 1, "SUC-001");

        LocalDate d = LocalDate.of(2026, 3, 11);
        for (int i = 0; i < 30; i++) {
            runner.runBusinessDay(d, "ALL");
            d = d.plusDays(1);
        }

        long ventanasDeDevengo = EventosCapturadosConfig.VENTANAS.stream()
                .filter(v -> v.startsWith("ACCRUAL|" + id)).count();
        assertThat(ventanasDeDevengo).isEqualTo(30);
    }
}

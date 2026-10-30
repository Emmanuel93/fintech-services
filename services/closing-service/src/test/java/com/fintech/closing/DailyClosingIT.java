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
@Import({InMemoryLockConfig.class, DailyClosingIT.CapturedEventsConfig.class})
class DailyClosingIT {

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
    static class CapturedEventsConfig {
        static final List<String> WINDOWS = new ArrayList<>();
        static final List<CutoffSchedule> CUTOFFS = new ArrayList<>();
        static final List<CloseSeal> SEALS = new ArrayList<>();

        @Bean @Primary
        ClosingEventPublisher capturador() {
            return new ClosingEventPublisher() {
                @Override public void publishUnitWindowOpened(UUID runId, UUID account, ClosePhase phase,
                                                               LocalDate date, String productType) {
                    WINDOWS.add(phase + "|" + account + "|" + date);
                }
                @Override public void publishCutoffClosed(CutoffSchedule c, UUID party, String productType) {
                    CUTOFFS.add(c);
                }
                @Override public void publishDaySealed(CloseSeal s) { SEALS.add(s); }
            };
        }
    }

    @Autowired BusinessDayRunner runner;
    @Autowired AccountProjectionService projection;
    @Autowired AccountCloseProfileRepository profiles;
    @Autowired CutoffScheduleRepository cutoffs;
    @Autowired CloseSealRepository seals;
    @Autowired JdbcTemplate jdbc;

    static final LocalDate ACTIVATED_ON = LocalDate.of(2026, 3, 10);

    @BeforeEach
    void cleanUp() {
        jdbc.update("DELETE FROM closing.close_units");
        jdbc.update("DELETE FROM closing.close_runs");
        jdbc.update("DELETE FROM closing.close_seals");
        jdbc.update("DELETE FROM closing.cutoff_schedules");
        jdbc.update("DELETE FROM closing.account_close_profiles");

        // Las políticas NO se borran —vienen sembradas por changeset— pero sí se reinicia el
        // porcentaje de pago mínimo, porque hay pruebas que lo cambian para ejercitar la
        // configuración. Sin esto, el UPDATE de una se filtra a la siguiente y el fallo aparece
        // en la prueba equivocada, que es la forma más caras de depurar.
        jdbc.update("UPDATE closing.close_cycle_policies SET minimum_payment_rate = 0.0500");
        CapturedEventsConfig.WINDOWS.clear();
        CapturedEventsConfig.CUTOFFS.clear();
        CapturedEventsConfig.SEALS.clear();
    }

    private UUID activate(String productType, String behavior, String cadence, Integer term,
                          BigDecimal balance) {
        UUID id = UUID.randomUUID();
        projection.onAccountActivated(id, UUID.randomUUID(), productType, behavior, "SUC-001",
                ACTIVATED_ON, cadence, term, new BigDecimal("0.32"), balance);
        return id;
    }

    @Test
    @DisplayName("un día de negocio corre sus fases en orden y las sella")
    void thedayRunsAndSeals() {
        activate("PERSONAL_LOAN", "INSTALLMENT", "MONTHLY", 12, new BigDecimal("20000"));

        var r = runner.runBusinessDay(LocalDate.of(2026, 3, 11), "ALL");

        assertThat(r.phases()).containsKeys(ClosePhase.RECONCILE, ClosePhase.ACCRUAL,
                ClosePhase.DELINQUENCY, ClosePhase.CUTOFF);
        assertThat(r.allSealed()).as("todas las fases sellaron").isTrue();
    }

    @Test
    @DisplayName("el devengo abre ventana por cuenta: el cierre NO devenga, lo pide")
    void elDevengoAbreVentana() {
        UUID a = activate("PERSONAL_LOAN", "INSTALLMENT", "MONTHLY", 12, new BigDecimal("20000"));
        UUID b = activate("MICRO_LOAN", "INSTALLMENT", "WEEKLY", 8, new BigDecimal("5000"));

        runner.runPhase(LocalDate.of(2026, 3, 11), ClosePhase.RECONCILE, "ALL");
        runner.runPhase(LocalDate.of(2026, 3, 11), ClosePhase.ACCRUAL, "ALL");

        assertThat(CapturedEventsConfig.WINDOWS)
                .contains("ACCRUAL|" + a + "|2026-03-11", "ACCRUAL|" + b + "|2026-03-11");
    }

    @Test
    @DisplayName("una cuenta sin saldo se omite y NO bloquea el sello")
    void withoutAbalanceItIsSkipped() {
        activate("PERSONAL_LOAN", "INSTALLMENT", "MONTHLY", 12, BigDecimal.ZERO);

        var phase = runner.runPhase(LocalDate.of(2026, 3, 11), ClosePhase.RECONCILE, "ALL");
        var accrual = runner.runPhase(LocalDate.of(2026, 3, 11), ClosePhase.ACCRUAL, "ALL");

        assertThat(accrual.omitidas()).isEqualTo(1);
        assertThat(accrual.fallidas()).isZero();
        assertThat(accrual.sellada()).as("omitida no bloquea el sello").isTrue();
    }

    @Test
    @DisplayName("una cuenta liquidada sale del cierre")
    void liquidadaSaleDelCierre() {
        UUID id = activate("PERSONAL_LOAN", "INSTALLMENT", "MONTHLY", 12, new BigDecimal("20000"));
        projection.onBalanceUpdated(id, BigDecimal.ZERO, BigDecimal.ZERO, "SETTLED", 10, "SUC-001");

        runner.runPhase(LocalDate.of(2026, 3, 11), ClosePhase.RECONCILE, "ALL");
        var accrual = runner.runPhase(LocalDate.of(2026, 3, 11), ClosePhase.ACCRUAL, "ALL");

        assertThat(accrual.units()).as("terminal no se materializa siquiera").isZero();
    }

    @Test
    @DisplayName("el corte se sella el día que toca, y sólo ese día")
    void thecutoffIsSealedOnItsDay() {
        UUID id = activate("PERSONAL_LOAN", "INSTALLMENT", "MONTHLY", 12, new BigDecimal("20000"));
        projection.onBalanceUpdated(id, new BigDecimal("20000"), new BigDecimal("20533.33"), "ACTIVE", 1, "SUC-001");

        // El día anterior al corte: no pasa nada.
        runner.runBusinessDay(LocalDate.of(2026, 4, 9), "ALL");
        assertThat(cutoffs.find(id, 1).orElseThrow().getStatus()).isEqualTo("SCHEDULED");

        // El día del corte: se sella.
        runner.runBusinessDay(LocalDate.of(2026, 4, 10), "ALL");
        CutoffSchedule cutoff = cutoffs.find(id, 1).orElseThrow();

        assertThat(cutoff.isSealed()).isTrue();
        assertThat(cutoff.getBalanceAtCutoff()).isEqualByComparingTo("20533.33");
        assertThat(CapturedEventsConfig.CUTOFFS).hasSize(1);
    }

    @Test
    @DisplayName("una tarjeta recibe pago mínimo; un préstamo a plazo NO lo inventa el cierre")
    void theminimumPaymentIsOnlyForRevolving() {
        // El importe de la cuota sale del plan de amortización, que es de cartera. Duplicar aquí
        // esa aritmética crearía una segunda fuente de verdad sobre el mismo número.
        UUID card = activate("CREDIT_CARD", "REVOLVING", null, null, new BigDecimal("10000"));
        UUID loan = activate("PERSONAL_LOAN", "INSTALLMENT", "MONTHLY", 12, new BigDecimal("20000"));
        projection.onBalanceUpdated(card, new BigDecimal("10000"), new BigDecimal("10000"), "ACTIVE", 1, "SUC-001");
        projection.onBalanceUpdated(loan, new BigDecimal("20000"), new BigDecimal("20000"), "ACTIVE", 1, "SUC-001");

        runner.runBusinessDay(LocalDate.of(2026, 4, 10), "ALL");

        assertThat(cutoffs.find(card, 1).orElseThrow().getMinimumPayment())
                .as("el 5% que configura la política del producto").isEqualByComparingTo("500.00");
        assertThat(cutoffs.find(loan, 1).orElseThrow().getMinimumPayment())
                .as("el cierre no inventa el importe de la cuota").isNull();
    }

    @Test
    @DisplayName("tras sellar un corte, el ciclo siguiente queda agendado")
    void thecycleGoesOn() {
        UUID id = activate("CREDIT_CARD", "REVOLVING", null, null, new BigDecimal("10000"));
        projection.onBalanceUpdated(id, new BigDecimal("10000"), new BigDecimal("10000"), "ACTIVE", 1, "SUC-001");

        runner.runBusinessDay(LocalDate.of(2026, 4, 10), "ALL");

        assertThat(cutoffs.find(id, 2)).isPresent();
        // El 10 de mayo de 2026 es DOMINGO: la política corre el corte al lunes siguiente.
        // La expectativa ingenua era el 10; el motor hizo lo correcto.
        assertThat(profiles.findById(id).orElseThrow().getNextCutoffDate())
                .isEqualTo(LocalDate.of(2026, 5, 11));
    }

    @Test
    @DisplayName("el sello lleva cifras de control y una huella que detecta alteración")
    void thesealCarriesItsFigures() {
        activate("PERSONAL_LOAN", "INSTALLMENT", "MONTHLY", 12, new BigDecimal("20000"));
        activate("MICRO_LOAN", "INSTALLMENT", "WEEKLY", 8, new BigDecimal("5000"));

        runner.runBusinessDay(LocalDate.of(2026, 3, 11), "ALL");

        CloseSeal seal = seals.find(LocalDate.of(2026, 3, 11), ClosePhase.ACCRUAL, "ALL").orElseThrow();
        assertThat(seal.getTotalPrincipal()).isEqualByComparingTo("25000");
        assertThat(seal.getUnitCount()).isEqualTo(2);
        assertThat(seal.isIntact()).as("la huella corresponde al contenido").isTrue();
    }

    @Test
    @DisplayName("correr el mismo día dos veces no reprocesa ni re-sella")
    void thedayIsIdempotent() {
        activate("PERSONAL_LOAN", "INSTALLMENT", "MONTHLY", 12, new BigDecimal("20000"));

        runner.runBusinessDay(LocalDate.of(2026, 3, 11), "ALL");
        int firstWindows = CapturedEventsConfig.WINDOWS.size();
        int firstSeals   = CapturedEventsConfig.SEALS.size();

        runner.runBusinessDay(LocalDate.of(2026, 3, 11), "ALL");

        assertThat(CapturedEventsConfig.WINDOWS).hasSize(firstWindows);
        assertThat(CapturedEventsConfig.SEALS).hasSize(firstSeals);
    }

    @Test
    @DisplayName("correr treinta días seguidos abre treinta ventanas de devengo, una por día")
    void thirtyDaysThirtyWindows() {
        // Es el requisito literal: cada devengamiento tiene que haber sido aplicado por un cierre.
        UUID id = activate("PERSONAL_LOAN", "INSTALLMENT", "MONTHLY", 12, new BigDecimal("20000"));
        projection.onBalanceUpdated(id, new BigDecimal("20000"), new BigDecimal("20000"), "ACTIVE", 1, "SUC-001");

        LocalDate d = LocalDate.of(2026, 3, 11);
        for (int i = 0; i < 30; i++) {
            runner.runBusinessDay(d, "ALL");
            d = d.plusDays(1);
        }

        long ventanasDeDevengo = CapturedEventsConfig.WINDOWS.stream()
                .filter(v -> v.startsWith("ACCRUAL|" + id)).count();
        assertThat(ventanasDeDevengo).isEqualTo(30);
    }
    // ── El pago mínimo sale de la POLÍTICA, no de una constante ──────────────────────────────

    @Test
    @DisplayName("cambiar el porcentaje de la política cambia el mínimo, sin desplegar nada")
    void theminimumPaymentRateComesFromThePolicy() {
        // Era `private static final BigDecimal MINIMUM_PAYMENT_RATE = new BigDecimal("0.05")`: el
        // mismo 5 % para una tarjeta de nómina y una de consumo, y cambiarlo exigía un despliegue.
        // Esta prueba es la que demuestra que ya no.
        jdbc.update("""
                UPDATE closing.close_cycle_policies SET minimum_payment_rate = 0.1000
                 WHERE scope_value = 'CREDIT_CARD'
                """);

        UUID card = activate("CREDIT_CARD", "REVOLVING", "MONTHLY", null, new BigDecimal("10000"));
        projection.onBalanceUpdated(card, new BigDecimal("10000"), new BigDecimal("10000"),
                "ACTIVE", 1, "SUC-001");

        runner.runBusinessDay(LocalDate.of(2026, 4, 10), "ALL");

        assertThat(cutoffs.find(card, 1).orElseThrow().getMinimumPayment())
                .as("el 10% que ahora exige la política, no el 5% que decía el código")
                .isEqualByComparingTo("1000.00");
    }

    @Test
    @DisplayName("una política sin porcentaje sella el corte SIN mínimo, en vez de inventar uno")
    void apolicyWithoutArateSealsWithoutAminimum() {
        // Nulo significa «este producto no exige mínimo», que es un hecho del producto. Caer a un
        // 5 % por defecto sería exigirle al cliente un importe que nadie configuró.
        jdbc.update("""
                UPDATE closing.close_cycle_policies SET minimum_payment_rate = NULL
                 WHERE scope_value = 'CREDIT_CARD'
                """);

        UUID card = activate("CREDIT_CARD", "REVOLVING", "MONTHLY", null, new BigDecimal("10000"));
        projection.onBalanceUpdated(card, new BigDecimal("10000"), new BigDecimal("10000"),
                "ACTIVE", 1, "SUC-001");

        runner.runBusinessDay(LocalDate.of(2026, 4, 10), "ALL");

        var sealed = cutoffs.find(card, 1).orElseThrow();
        assertThat(sealed.getMinimumPayment()).isNull();
        assertThat(sealed.getAmountDue())
                .as("el exigible del ciclo sigue siendo el saldo: lo que falta es el mínimo, no el total")
                .isEqualByComparingTo("10000.00");
    }

}

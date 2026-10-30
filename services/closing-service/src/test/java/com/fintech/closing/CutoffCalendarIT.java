package com.fintech.closing;

import com.fintech.closing.application.port.out.AccountCloseProfileRepository;
import com.fintech.closing.application.port.out.CutoffScheduleRepository;
import com.fintech.closing.application.service.AccountProjectionService;
import com.fintech.closing.domain.CutoffSchedule;
import com.fintech.closing.domain.PaymentCadence;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import com.fintech.shared.testing.AbstractIntegrationTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TK-06 — el calendario de corte lo <b>deriva y persiste el cierre</b>, no lo lee de cartera.
 *
 * <p>Es la prueba de la decisión de fondo: aunque para un no revolvente la cadencia coincida con el
 * vencimiento de la cuota, el cierre no consulta {@code installments.due_date}. Deriva de la
 * política del producto y de lo que aprendió por evento.
 */
@SpringBootTest
@Import(InMemoryLockConfig.class)
class CutoffCalendarIT extends AbstractIntegrationTest {

    /** El truncado entre pruebas sustituye a los DELETE manuales del @BeforeEach. */
    @Override
    protected java.util.List<String> schemas() {
        return java.util.List.of("closing");
    }

    /** Lo que siembra el changeset 010 y sin lo cual no hay política que resolver. */
    @Override
    protected java.util.List<String> preserve() {
        return java.util.List.of("business_calendars", "calendar_days", "close_cycle_policies");
    }

    @DynamicPropertySource
    static void withoutRedis(DynamicPropertyRegistry r) {
        r.add("spring.autoconfigure.exclude",
                () -> "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                    + "org.redisson.spring.starter.RedissonAutoConfigurationV2");
    }

    @Autowired AccountProjectionService projection;
    @Autowired CutoffScheduleRepository cutoffs;
    @Autowired AccountCloseProfileRepository profiles;
    @Autowired JdbcTemplate jdbc;

    static final LocalDate ACTIVATED_ON = LocalDate.of(2026, 3, 10);   // martes

    private UUID activate(String productType, String behavior, String cadence, Integer term) {
        UUID id = UUID.randomUUID();
        projection.onAccountActivated(id, UUID.randomUUID(), productType, behavior, "SUC-001",
                ACTIVATED_ON, cadence, term, new BigDecimal("0.32"), new BigDecimal("20000.00"));
        return id;
    }

    // ── No revolventes: la cadencia del plan, derivada por el cierre ──────────

    @Test
    @DisplayName("PERSONAL_LOAN mensual: 12 cortes, el primero un mes después del alta")
    void monthlyMeansTwelveCutoffs() {
        UUID id = activate("PERSONAL_LOAN", "INSTALLMENT", "MONTHLY", 12);
        List<CutoffSchedule> plan = cutoffs.findByAccount(id);

        assertThat(plan).hasSize(12);
        assertThat(plan.get(0).getCutoffDate()).isEqualTo(LocalDate.of(2026, 4, 10));
        assertThat(plan.get(11).getCutoffDate()).isEqualTo(LocalDate.of(2027, 3, 10));
    }

    @Test
    @DisplayName("PAYROLL_LOAN quincenal: el corte NO es mensual, es cada 14 días")
    void fortnightlyMeansEvery14Days() {
        UUID id = activate("PAYROLL_LOAN", "INSTALLMENT", "BIWEEKLY", 6);
        List<CutoffSchedule> plan = cutoffs.findByAccount(id);

        assertThat(plan).hasSize(6);
        assertThat(plan.get(0).getCutoffDate()).isEqualTo(LocalDate.of(2026, 3, 24));
        assertThat(plan.get(1).getCutoffDate()).isEqualTo(LocalDate.of(2026, 4, 7));
    }

    @Test
    @DisplayName("MICRO_LOAN semanal: 52 cortes al año")
    void weeklyMeansFiftyTwoCutoffsAyear() {
        UUID id = activate("MICRO_LOAN", "INSTALLMENT", "WEEKLY", 8);
        List<CutoffSchedule> plan = cutoffs.findByAccount(id);

        assertThat(plan).hasSize(8);
        assertThat(plan.get(0).getCutoffDate()).isEqualTo(LocalDate.of(2026, 3, 17));
        assertThat(plan.get(7).getCutoffDate()).isEqualTo(LocalDate.of(2026, 5, 5));
    }

    @Test
    @DisplayName("un préstamo a plazo no corta después de su última cuota")
    void itdoesNotCutBeyondTheTerm() {
        UUID id = activate("PERSONAL_LOAN", "INSTALLMENT", "MONTHLY", 3);
        assertThat(cutoffs.findByAccount(id)).hasSize(3);
    }

    // ── Revolventes: el ciclo que HOY no existe en la plataforma ──────────────

    @Test
    @DisplayName("CREDIT_CARD: ciclo anclado al día de activación, con fecha límite a 20 días")
    void acardCyclesFromActivation() {
        // Es el corte que hoy no existe: `hasCutoffDate` está sembrado en true y nadie lo lee.
        UUID id = activate("CREDIT_CARD", "REVOLVING", null, null);
        List<CutoffSchedule> plan = cutoffs.findByAccount(id);

        assertThat(plan).hasSize(24);
        assertThat(plan.get(0).getCutoffDate()).isEqualTo(LocalDate.of(2026, 4, 10));
        assertThat(plan.get(0).getPaymentDueDate())
                .as("corte + 20 días, corrido si cae inhábil")
                .isEqualTo(LocalDate.of(2026, 4, 30));
    }

    @Test
    @DisplayName("dos tarjetas activadas en días distintos cortan en días distintos")
    void cutoffsAreNotAlignedAcrossAccounts() {
        // Es la raíz del problema de conciliación: la cartera no cierra los mismos días. Por eso
        // el cuadre va por flujo sobre la fecha de negocio y no por corte.
        UUID a = activate("CREDIT_CARD", "REVOLVING", null, null);

        UUID b = UUID.randomUUID();
        projection.onAccountActivated(b, UUID.randomUUID(), "CREDIT_CARD", "REVOLVING", "SUC-002",
                LocalDate.of(2026, 3, 22), null, null, new BigDecimal("0.45"), new BigDecimal("15000"));

        assertThat(cutoffs.findByAccount(a).get(0).getCutoffDate()).isEqualTo(LocalDate.of(2026, 4, 10));
        assertThat(cutoffs.findByAccount(b).get(0).getCutoffDate()).isEqualTo(LocalDate.of(2026, 4, 22));
    }

    // ── Calendario de negocio ────────────────────────────────────────────────

    @Test
    @DisplayName("un corte que cae en sábado se corre al lunes")
    void acutoffOnANonBusinessDayShifts() {
        // Alta el 10 de enero de 2026 (sábado): el corte mensual caería el 10 de febrero (martes),
        // pero el de abril cae el 10 (viernes) y el de julio el 10 (viernes)... se usa una alta que
        // produzca un corte en fin de semana.
        UUID id = UUID.randomUUID();
        projection.onAccountActivated(id, UUID.randomUUID(), "PERSONAL_LOAN", "INSTALLMENT", "SUC-001",
                LocalDate.of(2026, 4, 11), "MONTHLY", 3, new BigDecimal("0.32"), new BigDecimal("10000"));

        // 11 de mayo de 2026 es lunes; 11 de julio es SÁBADO → se corre al lunes 13.
        List<CutoffSchedule> plan = cutoffs.findByAccount(id);
        assertThat(plan.get(0).getCutoffDate().getDayOfWeek().getValue()).isLessThanOrEqualTo(5);
        assertThat(plan).allSatisfy(c ->
                assertThat(c.getCutoffDate().getDayOfWeek().getValue())
                        .as("ningún corte cae en fin de semana con shift=NEXT")
                        .isLessThanOrEqualTo(5));
    }

    @Test
    @DisplayName("la fecha límite nunca queda antes del corte")
    void thedueDateIsNeverBeforeTheCutoff() {
        UUID id = activate("PERSONAL_LOAN", "INSTALLMENT", "MONTHLY", 12);
        assertThat(cutoffs.findByAccount(id)).allSatisfy(c ->
                assertThat(c.getPaymentDueDate()).isAfterOrEqualTo(c.getCutoffDate()));
    }

    // ── Idempotencia e inmutabilidad ─────────────────────────────────────────

    @Test
    @DisplayName("la reentrega del alta no duplica el calendario")
    void altaIdempotente() {
        UUID id = UUID.randomUUID();
        for (int i = 0; i < 3; i++) {
            projection.onAccountActivated(id, UUID.randomUUID(), "PERSONAL_LOAN", "INSTALLMENT",
                    "SUC-001", ACTIVATED_ON, "MONTHLY", 12, new BigDecimal("0.32"), new BigDecimal("20000"));
        }
        assertThat(cutoffs.findByAccount(id)).hasSize(12);
    }

    @Test
    @DisplayName("un corte sellado no se reabre")
    void asealedCutoffIsImmutable() {
        // Reabrirlo es lo que hace que el estado de cuenta del cliente cambie después de
        // habérselo mandado.
        UUID id = activate("PERSONAL_LOAN", "INSTALLMENT", "MONTHLY", 12);
        CutoffSchedule cutoff = cutoffs.find(id, 1).orElseThrow();

        cutoff.seal(new BigDecimal("18000"), new BigDecimal("533.33"), BigDecimal.ZERO,
                new BigDecimal("2100.00"), null, 30);
        cutoffs.save(cutoff);

        assertThatThrownBy(() -> cutoff.seal(BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO,
                        BigDecimal.TEN, null, 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no se reabre");
    }

    @Test
    @DisplayName("la consulta diaria devuelve sólo los cortes de ese día que siguen agendados")
    void thedailyQuery() {
        UUID id = activate("PERSONAL_LOAN", "INSTALLMENT", "MONTHLY", 12);

        assertThat(cutoffs.findDueOn(LocalDate.of(2026, 4, 10))).hasSize(1);
        assertThat(cutoffs.findDueOn(LocalDate.of(2026, 4, 11))).isEmpty();

        CutoffSchedule cutoff = cutoffs.find(id, 1).orElseThrow();
        cutoff.seal(BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.TEN, null, 1);
        cutoffs.save(cutoff);

        assertThat(cutoffs.findDueOn(LocalDate.of(2026, 4, 10)))
                .as("un corte sellado ya no aparece: no se repite").isEmpty();
    }

    // ── Proyección de saldo ──────────────────────────────────────────────────

    @Test
    @DisplayName("un balance-updated fuera de orden no retrocede el saldo recordado")
    void thebalanceDoesNotGoBackwards() {
        UUID id = activate("PERSONAL_LOAN", "INSTALLMENT", "MONTHLY", 12);

        projection.onBalanceUpdated(id, new BigDecimal("18000"), new BigDecimal("18500"), "ACTIVE", 5, "SUC-001");
        projection.onBalanceUpdated(id, new BigDecimal("19000"), new BigDecimal("19500"), "ACTIVE", 3, "SUC-001");

        var profile = profiles.findById(id).orElseThrow();
        assertThat(profile.getPrincipalBalance()).isEqualByComparingTo("18000");
        assertThat(profile.getLastBalanceVersion()).isEqualTo(5);
    }

    @Test
    @DisplayName("una cuenta liquidada queda terminal y sale de las corridas")
    void terminalAccount() {
        UUID id = activate("PERSONAL_LOAN", "INSTALLMENT", "MONTHLY", 12);
        projection.onBalanceUpdated(id, BigDecimal.ZERO, BigDecimal.ZERO, "SETTLED", 9, "SUC-001");

        assertThat(profiles.findById(id).orElseThrow().isTerminal()).isTrue();
        assertThat(profiles.findActiveForClosing(LocalDate.now(), 100))
                .noneMatch(p -> p.getCreditAccountId().equals(id));
    }
    // ── Revolventes: el ciclo lo fija la PERIODICIDAD DEL PRODUCTO ───────────────────────────

    @Test
    @DisplayName("una línea revolvente SEMANAL corta cada semana, no cada mes")
    void arevolvingWeeklyProductCutsWeekly() {
        // Es el defecto que esto arregla. Era `activatedOn.plusMonths(cycle)`: mensual a fuerza, con
        // lo que una línea configurada WEEKLY en el catálogo cortaba mensual y **nada fallaba** — el
        // calendario se derivaba, se persistía y se sellaba con la periodicidad equivocada.
        UUID id = activate("CREDIT_CARD", "REVOLVING", "WEEKLY", null);
        List<CutoffSchedule> plan = cutoffs.findByAccount(id);

        assertThat(plan.get(0).getCutoffDate()).isEqualTo(ACTIVATED_ON.plusWeeks(1));
        assertThat(plan.get(1).getCutoffDate()).isEqualTo(ACTIVATED_ON.plusWeeks(2));
        assertThat(plan.get(2).getCutoffDate()).isEqualTo(ACTIVATED_ON.plusWeeks(3));
    }

    @Test
    @DisplayName("y una QUINCENAL cada catorce días")
    void arevolvingBiweeklyProductCutsEveryFortnight() {
        UUID id = activate("CREDIT_CARD", "REVOLVING", "BIWEEKLY", null);
        List<CutoffSchedule> plan = cutoffs.findByAccount(id);

        assertThat(plan.get(0).getCutoffDate()).isEqualTo(ACTIVATED_ON.plusWeeks(2));
        assertThat(plan.get(1).getCutoffDate()).isEqualTo(ACTIVATED_ON.plusWeeks(4));
    }

    @Test
    @DisplayName("la MENSUAL sigue cortando mensual: el cambio no movió lo que ya funcionaba")
    void arevolvingMonthlyProductStillCutsMonthly() {
        UUID id = activate("CREDIT_CARD", "REVOLVING", "MONTHLY", null);
        List<CutoffSchedule> plan = cutoffs.findByAccount(id);

        assertThat(plan.get(0).getCutoffDate()).isEqualTo(LocalDate.of(2026, 4, 10));   // viernes

        // El segundo caería el 10 de mayo, que es DOMINGO, y la política corre al siguiente hábil.
        // Se afirma el 11 a propósito: la cadencia decide el día crudo y el calendario decide el
        // hábil, y son dos reglas distintas que conviene no confundir al leer esta prueba.
        assertThat(plan.get(1).getCutoffDate()).isEqualTo(LocalDate.of(2026, 5, 11));   // lunes
    }

    // ── El horizonte se mide en TIEMPO, no en número de cortes ───────────────────────────────

    @Test
    @DisplayName("el calendario cubre dos años con cualquier cadencia, no «24 cortes»")
    void thehorizonIsTwoYearsRegardlessOfCadence() {
        // Eran 24 cortes fijos, pensados como 24 meses. Sobre un producto semanal esos 24 cortes se
        // agotan en cinco meses, y la cuenta se queda sin calendario a mitad del primer año.
        assertThat(cutoffs.findByAccount(activate("CREDIT_CARD", "REVOLVING", "MONTHLY", null)))
                .as("mensual: 12 × 2").hasSize(24);
        assertThat(cutoffs.findByAccount(activate("CREDIT_CARD", "REVOLVING", "BIWEEKLY", null)))
                .as("quincenal: 26 × 2").hasSize(52);
        assertThat(cutoffs.findByAccount(activate("CREDIT_CARD", "REVOLVING", "WEEKLY", null)))
                .as("semanal: 52 × 2").hasSize(104);
    }

    // ── Y «sin configurar» no es «mensual», aunque se comporte así ───────────────────────────

    @Test
    @DisplayName("un producto sin periodicidad declarada cae a mensual, y el perfil lo DICE")
    void anundeclaredCadenceFallsBackToMonthlyAndSaysSo() {
        // Caer a mensual es correcto —es lo que hace cartera al amortizar— pero «no configurado» y
        // «configurado mensual» son hechos distintos, y antes producían el mismo resultado sin dejar
        // rastro. Si una tarjeta semanal llegara sin el dato, su calendario saldría mensual y la
        // única forma de enterarse sería comparar el catálogo con los cortes sellados a mano.
        UUID id = activate("CREDIT_CARD", "REVOLVING", null, null);

        var profile = profiles.findById(id).orElseThrow();
        assertThat(profile.hasDeclaredCadence()).isFalse();
        assertThat(profile.cadence()).isEqualTo(PaymentCadence.MONTHLY);
        assertThat(cutoffs.findByAccount(id).get(0).getCutoffDate())
                .isEqualTo(ACTIVATED_ON.plusMonths(1));
    }

    @Test
    @DisplayName("una periodicidad que no existe tampoco pasa por mensual en silencio")
    void agarbageCadenceIsNotSilentlyMonthly() {
        UUID id = activate("CREDIT_CARD", "REVOLVING", "TRIMESTRAL", null);

        assertThat(profiles.findById(id).orElseThrow().hasDeclaredCadence())
                .as("el perfil sabe que lo que le llegó no es una cadencia reconocible")
                .isFalse();
    }

}

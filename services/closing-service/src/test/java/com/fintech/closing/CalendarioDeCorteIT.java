package com.fintech.closing;

import com.fintech.closing.application.port.out.AccountCloseProfileRepository;
import com.fintech.closing.application.port.out.CutoffScheduleRepository;
import com.fintech.closing.application.service.AccountProjectionService;
import com.fintech.closing.domain.CutoffSchedule;
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
class CalendarioDeCorteIT extends AbstractIntegrationTest {

    /** El truncado entre pruebas sustituye a los DELETE manuales del @BeforeEach. */
    @Override
    protected java.util.List<String> esquemas() {
        return java.util.List.of("closing");
    }

    /** Lo que siembra el changeset 010 y sin lo cual no hay política que resolver. */
    @Override
    protected java.util.List<String> preservar() {
        return java.util.List.of("business_calendars", "calendar_days", "close_cycle_policies");
    }

    @DynamicPropertySource
    static void sinRedis(DynamicPropertyRegistry r) {
        r.add("spring.autoconfigure.exclude",
                () -> "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                    + "org.redisson.spring.starter.RedissonAutoConfigurationV2");
    }

    @Autowired AccountProjectionService proyeccion;
    @Autowired CutoffScheduleRepository cortes;
    @Autowired AccountCloseProfileRepository perfiles;
    @Autowired JdbcTemplate jdbc;

    static final LocalDate ALTA = LocalDate.of(2026, 3, 10);   // martes

    private UUID activar(String productType, String behavior, String cadencia, Integer plazo) {
        UUID id = UUID.randomUUID();
        proyeccion.onAccountActivated(id, UUID.randomUUID(), productType, behavior, "SUC-001",
                ALTA, cadencia, plazo, new BigDecimal("0.32"), new BigDecimal("20000.00"));
        return id;
    }

    // ── No revolventes: la cadencia del plan, derivada por el cierre ──────────

    @Test
    @DisplayName("PERSONAL_LOAN mensual: 12 cortes, el primero un mes después del alta")
    void mensualDoceCortes() {
        UUID id = activar("PERSONAL_LOAN", "INSTALLMENT", "MONTHLY", 12);
        List<CutoffSchedule> plan = cortes.findByAccount(id);

        assertThat(plan).hasSize(12);
        assertThat(plan.get(0).getCutoffDate()).isEqualTo(LocalDate.of(2026, 4, 10));
        assertThat(plan.get(11).getCutoffDate()).isEqualTo(LocalDate.of(2027, 3, 10));
    }

    @Test
    @DisplayName("PAYROLL_LOAN quincenal: el corte NO es mensual, es cada 14 días")
    void quincenalCada14Dias() {
        UUID id = activar("PAYROLL_LOAN", "INSTALLMENT", "BIWEEKLY", 6);
        List<CutoffSchedule> plan = cortes.findByAccount(id);

        assertThat(plan).hasSize(6);
        assertThat(plan.get(0).getCutoffDate()).isEqualTo(LocalDate.of(2026, 3, 24));
        assertThat(plan.get(1).getCutoffDate()).isEqualTo(LocalDate.of(2026, 4, 7));
    }

    @Test
    @DisplayName("MICRO_LOAN semanal: 52 cortes al año")
    void semanal() {
        UUID id = activar("MICRO_LOAN", "INSTALLMENT", "WEEKLY", 8);
        List<CutoffSchedule> plan = cortes.findByAccount(id);

        assertThat(plan).hasSize(8);
        assertThat(plan.get(0).getCutoffDate()).isEqualTo(LocalDate.of(2026, 3, 17));
        assertThat(plan.get(7).getCutoffDate()).isEqualTo(LocalDate.of(2026, 5, 5));
    }

    @Test
    @DisplayName("un préstamo a plazo no corta después de su última cuota")
    void noCortaMasAllaDelPlazo() {
        UUID id = activar("PERSONAL_LOAN", "INSTALLMENT", "MONTHLY", 3);
        assertThat(cortes.findByAccount(id)).hasSize(3);
    }

    // ── Revolventes: el ciclo que HOY no existe en la plataforma ──────────────

    @Test
    @DisplayName("CREDIT_CARD: ciclo anclado al día de activación, con fecha límite a 20 días")
    void tarjetaCicloDesdeElAlta() {
        // Es el corte que hoy no existe: `hasCutoffDate` está sembrado en true y nadie lo lee.
        UUID id = activar("CREDIT_CARD", "REVOLVING", null, null);
        List<CutoffSchedule> plan = cortes.findByAccount(id);

        assertThat(plan).hasSize(24);
        assertThat(plan.get(0).getCutoffDate()).isEqualTo(LocalDate.of(2026, 4, 10));
        assertThat(plan.get(0).getPaymentDueDate())
                .as("corte + 20 días, corrido si cae inhábil")
                .isEqualTo(LocalDate.of(2026, 4, 30));
    }

    @Test
    @DisplayName("dos tarjetas activadas en días distintos cortan en días distintos")
    void cortesDesalineadosEntreCuentas() {
        // Es la raíz del problema de conciliación: la cartera no cierra los mismos días. Por eso
        // el cuadre va por flujo sobre la fecha de negocio y no por corte.
        UUID a = activar("CREDIT_CARD", "REVOLVING", null, null);

        UUID b = UUID.randomUUID();
        proyeccion.onAccountActivated(b, UUID.randomUUID(), "CREDIT_CARD", "REVOLVING", "SUC-002",
                LocalDate.of(2026, 3, 22), null, null, new BigDecimal("0.45"), new BigDecimal("15000"));

        assertThat(cortes.findByAccount(a).get(0).getCutoffDate()).isEqualTo(LocalDate.of(2026, 4, 10));
        assertThat(cortes.findByAccount(b).get(0).getCutoffDate()).isEqualTo(LocalDate.of(2026, 4, 22));
    }

    // ── Calendario de negocio ────────────────────────────────────────────────

    @Test
    @DisplayName("un corte que cae en sábado se corre al lunes")
    void corteEnInhabilSeCorre() {
        // Alta el 10 de enero de 2026 (sábado): el corte mensual caería el 10 de febrero (martes),
        // pero el de abril cae el 10 (viernes) y el de julio el 10 (viernes)... se usa una alta que
        // produzca un corte en fin de semana.
        UUID id = UUID.randomUUID();
        proyeccion.onAccountActivated(id, UUID.randomUUID(), "PERSONAL_LOAN", "INSTALLMENT", "SUC-001",
                LocalDate.of(2026, 4, 11), "MONTHLY", 3, new BigDecimal("0.32"), new BigDecimal("10000"));

        // 11 de mayo de 2026 es lunes; 11 de julio es SÁBADO → se corre al lunes 13.
        List<CutoffSchedule> plan = cortes.findByAccount(id);
        assertThat(plan.get(0).getCutoffDate().getDayOfWeek().getValue()).isLessThanOrEqualTo(5);
        assertThat(plan).allSatisfy(c ->
                assertThat(c.getCutoffDate().getDayOfWeek().getValue())
                        .as("ningún corte cae en fin de semana con shift=NEXT")
                        .isLessThanOrEqualTo(5));
    }

    @Test
    @DisplayName("la fecha límite nunca queda antes del corte")
    void limiteNuncaAntesDelCorte() {
        UUID id = activar("PERSONAL_LOAN", "INSTALLMENT", "MONTHLY", 12);
        assertThat(cortes.findByAccount(id)).allSatisfy(c ->
                assertThat(c.getPaymentDueDate()).isAfterOrEqualTo(c.getCutoffDate()));
    }

    // ── Idempotencia e inmutabilidad ─────────────────────────────────────────

    @Test
    @DisplayName("la reentrega del alta no duplica el calendario")
    void altaIdempotente() {
        UUID id = UUID.randomUUID();
        for (int i = 0; i < 3; i++) {
            proyeccion.onAccountActivated(id, UUID.randomUUID(), "PERSONAL_LOAN", "INSTALLMENT",
                    "SUC-001", ALTA, "MONTHLY", 12, new BigDecimal("0.32"), new BigDecimal("20000"));
        }
        assertThat(cortes.findByAccount(id)).hasSize(12);
    }

    @Test
    @DisplayName("un corte sellado no se reabre")
    void elCorteSelladoEsInmutable() {
        // Reabrirlo es lo que hace que el estado de cuenta del cliente cambie después de
        // habérselo mandado.
        UUID id = activar("PERSONAL_LOAN", "INSTALLMENT", "MONTHLY", 12);
        CutoffSchedule corte = cortes.find(id, 1).orElseThrow();

        corte.seal(new BigDecimal("18000"), new BigDecimal("533.33"), BigDecimal.ZERO,
                new BigDecimal("2100.00"), null, 30);
        cortes.save(corte);

        assertThatThrownBy(() -> corte.seal(BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO,
                        BigDecimal.TEN, null, 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no se reabre");
    }

    @Test
    @DisplayName("la consulta diaria devuelve sólo los cortes de ese día que siguen agendados")
    void laConsultaDiaria() {
        UUID id = activar("PERSONAL_LOAN", "INSTALLMENT", "MONTHLY", 12);

        assertThat(cortes.findDueOn(LocalDate.of(2026, 4, 10))).hasSize(1);
        assertThat(cortes.findDueOn(LocalDate.of(2026, 4, 11))).isEmpty();

        CutoffSchedule corte = cortes.find(id, 1).orElseThrow();
        corte.seal(BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.TEN, null, 1);
        cortes.save(corte);

        assertThat(cortes.findDueOn(LocalDate.of(2026, 4, 10)))
                .as("un corte sellado ya no aparece: no se repite").isEmpty();
    }

    // ── Proyección de saldo ──────────────────────────────────────────────────

    @Test
    @DisplayName("un balance-updated fuera de orden no retrocede el saldo recordado")
    void elSaldoNoRetrocede() {
        UUID id = activar("PERSONAL_LOAN", "INSTALLMENT", "MONTHLY", 12);

        proyeccion.onBalanceUpdated(id, new BigDecimal("18000"), new BigDecimal("18500"), "ACTIVE", 5, "SUC-001");
        proyeccion.onBalanceUpdated(id, new BigDecimal("19000"), new BigDecimal("19500"), "ACTIVE", 3, "SUC-001");

        var perfil = perfiles.findById(id).orElseThrow();
        assertThat(perfil.getPrincipalBalance()).isEqualByComparingTo("18000");
        assertThat(perfil.getLastBalanceVersion()).isEqualTo(5);
    }

    @Test
    @DisplayName("una cuenta liquidada queda terminal y sale de las corridas")
    void cuentaTerminal() {
        UUID id = activar("PERSONAL_LOAN", "INSTALLMENT", "MONTHLY", 12);
        proyeccion.onBalanceUpdated(id, BigDecimal.ZERO, BigDecimal.ZERO, "SETTLED", 9, "SUC-001");

        assertThat(perfiles.findById(id).orElseThrow().isTerminal()).isTrue();
        assertThat(perfiles.findActiveForClosing(LocalDate.now(), 100))
                .noneMatch(p -> p.getCreditAccountId().equals(id));
    }
}

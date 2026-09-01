package com.fintech.closing;

import com.fintech.closing.application.port.out.CalendarDayRepository;
import com.fintech.closing.application.service.BusinessCalendarService;
import com.fintech.closing.application.service.ClosePolicyResolver;
import com.fintech.closing.domain.AccrualBasis;
import com.fintech.closing.domain.CalendarDay;
import com.fintech.closing.domain.ClosePhase;
import com.fintech.closing.domain.ClosePolicy;
import com.fintech.closing.domain.CutoffRule;
import com.fintech.closing.domain.NonBusinessDayShift;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TK-04 — la fecha de negocio y la política del producto, contra Postgres real.
 *
 * <p>Es lo que hace posible todo lo demás: sin fecha de negocio no se puede reproducir el cierre
 * del día 15, y sin política congelada una re-corrida usaría la configuración de hoy para
 * recalcular el pasado.
 */
@SpringBootTest
@Testcontainers
@Import(InMemoryLockConfig.class)   // el servicio no arranca sin candado; aquí no se prueba el reparto
class CalendarioYPoliticaIT {

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

    @Autowired BusinessCalendarService calendario;
    @Autowired ClosePolicyResolver resolver;
    @Autowired CalendarDayRepository calendarDays;
    @Autowired JdbcTemplate jdbc;

    // ── Calendario ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("sin excepción cargada, entre semana se opera y el fin de semana no")
    void reglaGeneralSinCargarElAño() {
        // La tabla guarda sólo excepciones: un calendario nuevo funciona desde el primer día sin
        // tener que sembrarle 365 filas antes de poder cerrar nada.
        assertThat(calendario.isBusinessDay("MX", LocalDate.of(2026, 8, 24))).isTrue();   // lunes
        assertThat(calendario.isBusinessDay("MX", LocalDate.of(2026, 8, 22))).isFalse();  // sábado
        assertThat(calendario.isBusinessDay("MX", LocalDate.of(2026, 8, 23))).isFalse();  // domingo
    }

    @Test
    @DisplayName("un festivo cargado gana sobre la regla general")
    void elFestivoGana() {
        LocalDate independencia = LocalDate.of(2026, 9, 16);   // miércoles
        assertThat(calendario.isBusinessDay("MX", independencia)).isTrue();

        calendarDays.save(CalendarDay.of("MX", independencia, false, "Independencia"));

        assertThat(calendario.isBusinessDay("MX", independencia)).isFalse();
    }

    @Test
    @DisplayName("un corte en domingo se corre según la política del producto")
    void elCorteEnDomingoSeCorre() {
        LocalDate domingo = LocalDate.of(2026, 8, 23);

        assertThat(calendario.shift("MX", domingo, NonBusinessDayShift.NEXT))
                .as("NEXT: al lunes; no se le cobra al cliente un día que no operó")
                .isEqualTo(LocalDate.of(2026, 8, 24));

        assertThat(calendario.shift("MX", domingo, NonBusinessDayShift.PREV))
                .as("PREV: al viernes; se usa cuando el corte no puede cruzar el fin de mes")
                .isEqualTo(LocalDate.of(2026, 8, 21));

        assertThat(calendario.shift("MX", domingo, NonBusinessDayShift.NONE))
                .as("NONE: se queda; el devengo no necesita que el banco abra")
                .isEqualTo(domingo);
    }

    @Test
    @DisplayName("un día hábil no se mueve aunque la regla sea NEXT")
    void elHabilNoSeMueve() {
        LocalDate lunes = LocalDate.of(2026, 8, 24);
        assertThat(calendario.shift("MX", lunes, NonBusinessDayShift.NEXT)).isEqualTo(lunes);
    }

    @Test
    @DisplayName("sumar días hábiles salta el fin de semana")
    void sumarHabiles() {
        // Viernes + 3 hábiles = miércoles, no lunes.
        assertThat(calendario.plusBusinessDays("MX", LocalDate.of(2026, 8, 21), 3))
                .isEqualTo(LocalDate.of(2026, 8, 26));
        assertThat(calendario.businessDaysBetween("MX",
                LocalDate.of(2026, 8, 21), LocalDate.of(2026, 8, 26))).isEqualTo(3);
    }

    // ── Resolución de política ───────────────────────────────────────────────

    @Test
    @DisplayName("el tipo de producto gana sobre la global")
    void tipoDeProductoGanaSobreGlobal() {
        ClosePolicy p = resolver.require(null, "PERSONAL_LOAN", LocalDate.of(2026, 8, 24));

        assertThat(p.scopeType()).isEqualTo(ClosePolicy.ScopeType.PRODUCT_TYPE);
        assertThat(p.cutoffRule()).isEqualTo(CutoffRule.INSTALLMENT_DUE_DATE);
    }

    @Test
    @DisplayName("un producto sin política propia cae a la global")
    void sinPoliticaPropiaCaeAGlobal() {
        ClosePolicy p = resolver.require(null, "PRODUCTO_QUE_NO_EXISTE", LocalDate.of(2026, 8, 24));

        assertThat(p.scopeType()).isEqualTo(ClosePolicy.ScopeType.GLOBAL);
        assertThat(p.cutoffRule()).as("la global no define corte").isEqualTo(CutoffRule.NONE);
    }

    @Test
    @DisplayName("un productId concreto gana sobre su tipo")
    void elProductoConcretoGanaSobreElTipo() {
        String productId = "aaaaaaaa-0000-0000-0000-000000000001";
        jdbc.update("""
                INSERT INTO closing.close_cycle_policies
                    (scope_type, scope_value, version, status, effective_date, calendar_code,
                     phases, accrual_basis, cutoff_rule, payment_due_offset_days)
                VALUES ('PRODUCT', ?, 1, 'ACTIVE', DATE '2020-01-01', 'MX',
                        'RECONCILE,ACCRUAL,SEAL', 'THIRTY_360', 'DAY_OF_MONTH', 5)
                """, productId);
        jdbc.update("UPDATE closing.close_cycle_policies SET cutoff_day = 15 WHERE scope_value = ?", productId);

        ClosePolicy p = resolver.require(productId, "PERSONAL_LOAN", LocalDate.of(2026, 8, 24));

        assertThat(p.scopeType()).isEqualTo(ClosePolicy.ScopeType.PRODUCT);
        assertThat(p.accrualBasis()).isEqualTo(AccrualBasis.THIRTY_360);
        assertThat(p.getCutoffDay()).isEqualTo((short) 15);
    }

    @Test
    @DisplayName("se usa la política vigente EL DÍA DEL CIERRE, no la de hoy")
    void laVigenteElDiaDelCierre() {
        // Si no, un cambio de política reescribiría el pasado en la siguiente re-corrida y el
        // número ya publicado dejaría de reproducirse.
        jdbc.update("""
                INSERT INTO closing.close_cycle_policies
                    (scope_type, scope_value, version, status, effective_date, calendar_code,
                     phases, accrual_basis, cutoff_rule)
                VALUES ('PRODUCT_TYPE', 'GROUP_LOAN', 1, 'ACTIVE', DATE '2027-01-01', 'MX',
                        'ACCRUAL', 'THIRTY_360', 'NONE')
                """);

        // El 2026-08-24 esa política todavía no existía: cae a la global.
        assertThat(resolver.require(null, "GROUP_LOAN", LocalDate.of(2026, 8, 24)).scopeType())
                .isEqualTo(ClosePolicy.ScopeType.GLOBAL);

        // En 2027 sí aplica.
        assertThat(resolver.require(null, "GROUP_LOAN", LocalDate.of(2027, 6, 1)).scopeType())
                .isEqualTo(ClosePolicy.ScopeType.PRODUCT_TYPE);
    }

    @Test
    @DisplayName("sin política vigente se falla explícito, no se inventa una por defecto")
    void sinPoliticaFallaExplicito() {
        // Inventarle un default a un producto que no la declaró es cómo se acaba devengando con
        // una convención que nadie eligió.
        jdbc.update("UPDATE closing.close_cycle_policies SET status = 'RETIRED' WHERE scope_type = 'GLOBAL'");

        assertThatThrownBy(() -> resolver.require(null, "LO_QUE_SEA", LocalDate.of(2026, 8, 24)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Sin política de cierre")
                .hasMessageContaining("GLOBAL");

        jdbc.update("UPDATE closing.close_cycle_policies SET status = 'ACTIVE' WHERE scope_type = 'GLOBAL'");
    }

    @Test
    @DisplayName("las fases del producto se leen tipadas y en orden")
    void fasesTipadas() {
        ClosePolicy p = resolver.require(null, "CREDIT_CARD", LocalDate.of(2026, 8, 24));

        assertThat(p.phaseList()).startsWith(ClosePhase.RECONCILE, ClosePhase.ACCRUAL);
        assertThat(p.hasPhase(ClosePhase.CUTOFF)).as("una tarjeta sí tiene corte").isTrue();
        assertThat(p.cutoffRule()).isEqualTo(CutoffRule.CYCLE_FROM_ACTIVATION);

        ClosePolicy prestamo = resolver.require(null, "PERSONAL_LOAN", LocalDate.of(2026, 8, 24));
        assertThat(prestamo.cutoffRule()).isEqualTo(CutoffRule.INSTALLMENT_DUE_DATE);
    }

    @Test
    @DisplayName("la línea de distribuidor no tolera descuadre: se paga a un tercero")
    void distribuidorConToleranciaCero() {
        ClosePolicy p = resolver.require(null, "DISTRIBUTOR_LINE", LocalDate.of(2026, 8, 24));

        assertThat(p.getReconcileTolerance()).isEqualByComparingTo("0.00");
        assertThat(p.onUnreconciled()).isEqualTo(ClosePolicy.OnUnreconciled.BLOCK_SEAL);
        assertThat(p.hasPhase(ClosePhase.COMMISSION)).isTrue();
    }
}

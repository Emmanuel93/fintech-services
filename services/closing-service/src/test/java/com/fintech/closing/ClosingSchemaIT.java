package com.fintech.closing;

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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El esquema aplica y trae lo que el motor necesita para arrancar.
 *
 * <p>No es una prueba de humo: verifica las <b>restricciones</b> que sostienen el diseño. Un
 * changelog que aplica pero deja fuera el índice único de la política vigente produce un cierre que
 * depende del orden en que la base devuelve las filas, y eso no se descubre hasta que dos pólizas
 * del mismo producto conviven.
 */
@SpringBootTest
@Testcontainers
@Import(InMemoryLockConfig.class)   // el servicio no arranca sin candado; aquí no se prueba el reparto
class ClosingSchemaIT {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("fintech").withUsername("fintech").withPassword("fintech");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // El candado no hace falta para validar el esquema, y exigir Redis aquí ataría esta
        // prueba a un contenedor más sin ganar nada.
        registry.add("spring.autoconfigure.exclude",
                () -> "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                    + "org.redisson.spring.starter.RedissonAutoConfigurationV2");
    }

    @Autowired JdbcTemplate jdbc;

    @Test
    @DisplayName("el changelog crea las nueve tablas del motor y NINGUNA de bancos")
    void elEsquemaAplica() {
        List<String> tablas = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'closing' ORDER BY 1",
                String.class);

        assertThat(tablas).containsExactly(
                "account_close_profiles", "business_calendars", "calendar_days",
                "close_cycle_policies", "close_runs", "close_seals", "close_units",
                "cutoff_schedules", "reconciliation_findings");

        // BK-04: las cinco `bank_*` se mudaron a `banking`. Afirmarlo por ausencia y no sólo por
        // omisión de la lista: si un merge reviviera el changeset retirado, esta línea lo dice.
        assertThat(tablas).noneMatch(t -> t.startsWith("bank_") || t.equals("suspense_entries"));
    }

    @Test
    @DisplayName("la conciliación contempla las tres puntas: cartera, contabilidad y bancos")
    void lasTresConciliaciones() {
        // El hueco que este servicio cierra: no hay servicio de bancos, y la conciliación a tres
        // puntas no existía en ninguna parte. `publishReconciliationAlert` estaba declarado en
        // accounting y no lo invocaba ni una línea.
        String check = jdbc.queryForObject("""
                SELECT pg_get_constraintdef(oid) FROM pg_constraint
                 WHERE conname = 'chk_finding_check'
                """, String.class);

        assertThat(check)
                .contains("PORTFOLIO_VS_LEDGER")
                .contains("LEDGER_VS_BANK")
                .contains("PORTFOLIO_VS_BANK");
    }

    @Test
    @DisplayName("BK-21 · una política dada de alta sin gracia explícita nace con TRES días")
    void laGraciaNoVuelveADivergir() {
        // El default decía 0 mientras charges y el propio seed decían 3. Coincidían sólo mientras
        // alguien especificara la columna: la primera política insertada sin ella —desde el
        // backoffice, desde un script— dejaba a esas cuentas en mora un día después del
        // vencimiento, con las vecinas teniendo tres. Un default que sólo falla cuando nadie mira.
        String def = jdbc.queryForObject("""
                SELECT column_default FROM information_schema.columns
                 WHERE table_schema = 'closing' AND table_name = 'close_cycle_policies'
                   AND column_name = 'payment_due_offset_days'
                """, String.class);

        assertThat(def).startsWith("3");
    }

    @Test
    @DisplayName("hay una sola política ACTIVE por alcance y el índice parcial lo impone")
    void unaSolaPoliticaActivaPorAlcance() {
        // La segunda ACTIVE del mismo producto tiene que rebotar: si conviven dos, cuál gana
        // depende del orden de las filas, y el cierre deja de ser reproducible.
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM pg_indexes
                 WHERE schemaname = 'closing' AND indexname = 'uq_policy_active'
                """, Integer.class)).isEqualTo(1);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO closing.close_cycle_policies
                    (scope_type, scope_value, version, status, effective_date, calendar_code,
                     phases, accrual_basis, cutoff_rule)
                VALUES ('PRODUCT_TYPE','PERSONAL_LOAN',2,'ACTIVE',DATE '2026-01-01','MX',
                        'ACCRUAL','ACTUAL_360','INSTALLMENT_DUE_DATE')
                """)).hasMessageContaining("uq_policy_active");
    }

    @Test
    @DisplayName("las políticas sembradas cubren revolventes y no revolventes")
    void politicasSembradas() {
        assertThat(jdbc.queryForObject("""
                SELECT cutoff_rule FROM closing.close_cycle_policies
                 WHERE scope_type='PRODUCT_TYPE' AND scope_value='PERSONAL_LOAN' AND status='ACTIVE'
                """, String.class)).isEqualTo("INSTALLMENT_DUE_DATE");

        assertThat(jdbc.queryForObject("""
                SELECT cutoff_rule FROM closing.close_cycle_policies
                 WHERE scope_type='PRODUCT_TYPE' AND scope_value='CREDIT_CARD' AND status='ACTIVE'
                """, String.class)).isEqualTo("CYCLE_FROM_ACTIVATION");

        // La convención de devengo queda DECLARADA por producto. Era lo que faltaba: el plan de
        // pagos usa 30/360 y el devengo actual/360, y nada lo decía.
        assertThat(jdbc.queryForList(
                "SELECT DISTINCT accrual_basis FROM closing.close_cycle_policies", String.class))
                .containsExactly("ACTUAL_360");
    }

    @Test
    @DisplayName("una corrida no puede duplicarse para la misma fecha, fase y alcance")
    void corridaUnicaPorFechaYFase() {
        jdbc.update("INSERT INTO closing.close_runs (business_date, phase) VALUES (DATE '2026-08-24','ACCRUAL')");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO closing.close_runs (business_date, phase) VALUES (DATE '2026-08-24','ACCRUAL')"))
                .hasMessageContaining("uq_close_run");
    }

    @Test
    @DisplayName("la fecha límite de un corte nunca es anterior al corte")
    void limiteNuncaAntesDelCorte() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO closing.cutoff_schedules
                    (credit_account_id, cycle_number, cutoff_date, payment_due_date)
                VALUES (gen_random_uuid(), 1, DATE '2026-08-20', DATE '2026-08-15')
                """)).hasMessageContaining("chk_cutoff_due_after");
    }
}

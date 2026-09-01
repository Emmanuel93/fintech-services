package com.fintech.banking;

import com.fintech.shared.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import com.fintech.banking.domain.Clabe;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * El esquema aplica y trae las restricciones que sostienen el diseño.
 *
 * <p>Hereda de {@code ClosingSchemaIT} la prueba de idempotencia de la ingesta: se mudó con las
 * tablas (BK-04). Era la única lectora que tenían las cinco {@code bank_*} en todo el monorepo.
 */
@SpringBootTest
class BankingSchemaIT extends AbstractIntegrationTest {

    @Override protected List<String> esquemas() { return List.of("banking"); }

    /**
     * El seed de ambiente bajo sobrevive al truncado. Sin esto, la primera prueba borra la cuenta y
     * la ruta que sembró Liquibase y a partir de ahí todo falla con «sin ruta» — el síntoma
     * desconcertante que documenta {@code AbstractIntegrationTest#preservar()}.
     */
    @Override protected List<String> preservar() { return List.of("bank_accounts", "payout_routes"); }

    @Test
    @DisplayName("el changelog crea las ocho tablas del dominio")
    void elEsquemaAplica() {
        List<String> tablas = jdbcTemplate.queryForList("""
                SELECT table_name FROM information_schema.tables
                 WHERE table_schema = 'banking' AND table_type = 'BASE TABLE' ORDER BY 1
                """, String.class);

        assertThat(tablas).containsExactly(
                "bank_accounts", "bank_close_seals", "bank_matches", "bank_statement_lines",
                // La proyección de hechos internos que la conciliación cruza (BK-38): conciliar es
                // barrer un día entero, y una consulta por línea al vecino ataría un proceso por
                // lotes a la disponibilidad de otro servicio.
                "internal_movements",
                // La cadena del dinero enhebrada (BK-41): los eslabones existían, recorrerlos
                // exigía abrir cinco servicios.
                "movement_trace",
                "payout_routes", "suspense_entries");
    }

    @Test
    @DisplayName("un movimiento bancario no se reingiere duplicado")
    void ingestaIdempotente() {
        UUID cuenta = insertarCuenta();
        String sql = """
                INSERT INTO banking.bank_statement_lines
                    (bank_account_id, business_date, direction, amount, external_id)
                VALUES (?, DATE '2026-08-24','CREDIT',5000,'MOV-001')
                """;
        jdbcTemplate.update(sql, cuenta);

        // La reingesta del mismo archivo es la norma, no la excepción: los bancos reenvían.
        assertThatThrownBy(() -> jdbcTemplate.update(sql, cuenta))
                .hasMessageContaining("uq_bank_line_external");
    }

    @Test
    @DisplayName("un movimiento bancario se cruza UNA sola vez")
    void cruceUnico() {
        UUID cuenta = insertarCuenta();
        UUID linea  = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO banking.bank_statement_lines
                    (line_id, bank_account_id, business_date, direction, amount, external_id)
                VALUES (?, ?, DATE '2026-08-24','CREDIT',5000,'MOV-002')
                """, linea, cuenta);
        jdbcTemplate.update("""
                INSERT INTO banking.bank_matches (line_id, internal_type, internal_ref, amount, method)
                VALUES (?,'STP_ORDER','CR-123',5000,'DETERMINISTIC')
                """, linea);

        // Sin `uq_bank_match_line`, un reintento del matcher cuadraría el mismo abono contra dos
        // hechos internos y la conciliación diría que todo cuadra habiendo cobrado una vez.
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO banking.bank_matches (line_id, internal_type, internal_ref, amount, method)
                VALUES (?,'PAYMENT_ORDER','PG-999',5000,'HEURISTIC')
                """, linea)).hasMessageContaining("uq_bank_match_line");
    }

    @Test
    @DisplayName("el cruce guarda SIEMPRE cómo se cruzó")
    void elMetodoEsObligatorio() {
        // Una conciliación que no puede explicar por qué cuadró dos importes no es auditable, y la
        // heurística hay que poder revisarla después de que alguien la aprobó.
        String check = jdbcTemplate.queryForObject("""
                SELECT pg_get_constraintdef(oid) FROM pg_constraint
                 WHERE conname = 'chk_bank_match_method'
                """, String.class);

        assertThat(check).contains("DETERMINISTIC").contains("HEURISTIC").contains("MANUAL");
    }

    @Test
    @DisplayName("el sello bancario es único por cuenta, fecha y periodicidad")
    void selloUnico() {
        String def = jdbcTemplate.queryForObject("""
                SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'uq_bank_seal'
                """, String.class);

        assertThat(def).contains("bank_account_id").contains("business_date").contains("period_type");
    }

    /**
     * Una cuenta nueva por prueba.
     *
     * <p>`preservar()` deja viva la tabla, y con ella lo que dejó la prueba anterior: un id fijo
     * chocaría con su propio rastro en la segunda ejecución.
     */
    private UUID insertarCuenta() {
        UUID id = UUID.randomUUID();
        String base = "012180" + String.format("%011d", Math.abs(id.getLeastSignificantBits() % 100_000_000_000L));
        jdbcTemplate.update("""
                INSERT INTO banking.bank_accounts
                    (bank_account_id, institution_code, institution_name, clabe, holder_name, ledger_account)
                VALUES (?,'012','BBVA', ?, 'FINTECH SA DE CV','1101')
                """, id, base + Clabe.digitoVerificador(base));
        return id;
    }
}

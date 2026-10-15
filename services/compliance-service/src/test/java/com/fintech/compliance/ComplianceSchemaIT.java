package com.fintech.compliance;

import com.fintech.compliance.application.service.WatchlistLoadService;
import com.fintech.compliance.domain.WatchlistKind;
import com.fintech.shared.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * El esquema y las entidades dicen lo mismo, y los invariantes están también en la base.
 *
 * <p>Con {@code ddl-auto: validate}, que el contexto arranque ya prueba que el mapeo corresponde a
 * lo que creó Liquibase. Lo que estas pruebas añaden es lo que un mapeo correcto no garantiza:
 * que las reglas que el dominio promete estén <b>también</b> en la base, para que se cumplan aunque
 * alguien escriba por otro camino.
 */
@SpringBootTest
class ComplianceSchemaIT extends AbstractIntegrationTest {

    @Override protected List<String> schemas() { return List.of("compliance"); }

    @Autowired WatchlistLoadService loads;

    @Test
    @DisplayName("una carga completa deja versión ACTIVE, sus filas y sus rechazos")
    void aFullLoadPersistsEverything() {
        var result = loads.load("OFAC-SDN", WatchlistKind.SANCTIONS, LocalDate.of(2026, 9, 1));

        assertThat(result.activated()).isTrue();

        Integer rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM compliance.watchlist_entries", Integer.class);
        assertThat(rows).isEqualTo(result.acceptedRows());

        Integer malas = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM compliance.watchlist_row_rejections", Integer.class);
        assertThat(malas)
                .as("el mock entrega un archivo sucio: si esto es cero, el mock dejó de servir")
                .isEqualTo(result.rejectedRows()).isGreaterThan(0);
    }

    @Test
    @DisplayName("la base impide dos versiones vigentes de la misma lista")
    void theDatabaseForbidsTwoActiveVersions() {
        // Es el invariante que hace que «la lista de hoy» sea una pregunta con UNA respuesta. El
        // servicio ya jubila la anterior antes de activar; esto comprueba que si algún día otro
        // camino escribe sin hacerlo, la base lo detiene en vez de dejar dos vigentes.
        loads.load("OFAC-SDN", WatchlistKind.SANCTIONS, LocalDate.of(2026, 9, 1));

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO compliance.watchlist_versions
                    (version_id, source_code, kind, file_hash, status, total_rows, accepted_rows,
                     rejected_rows, started_at, activated_at)
                VALUES (gen_random_uuid(), 'OFAC-SDN', 'SANCTIONS', 'otro-hash', 'ACTIVE',
                        1, 1, 0, now(), now())
                """)).hasMessageContaining("uq_watchlist_active_por_lista");
    }

    @Test
    @DisplayName("la base impide cargar dos veces el mismo archivo")
    void theDatabaseForbidsLoadingTheSameFileTwice() {
        // La idempotencia está en el servicio, que comprueba antes de escribir. Eso no basta con dos
        // sincronizaciones solapadas: las dos leen antes de que la otra escriba y las dos insertan.
        // El índice único es lo que de verdad lo impide.
        loads.load("PEP-MX", WatchlistKind.PEP, LocalDate.of(2026, 9, 1));

        String hash = jdbcTemplate.queryForObject(
                "SELECT file_hash FROM compliance.watchlist_versions WHERE source_code='PEP-MX'", String.class);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO compliance.watchlist_versions
                    (version_id, source_code, kind, file_hash, status, total_rows, accepted_rows,
                     rejected_rows, started_at)
                VALUES (gen_random_uuid(), 'PEP-MX', 'PEP', ?, 'LOADING', 0, 0, 0, now())
                """, hash)).hasMessageContaining("uq_watchlist_version_hash");
    }

    @Test
    @DisplayName("una versión FALLIDA sin motivo no puede existir")
    void aFailedVersionMustSayWhy() {
        // «Falló» sin motivo no es información: obliga a reproducir el fallo para saber qué pasó.
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO compliance.watchlist_versions
                    (version_id, source_code, kind, file_hash, status, total_rows, accepted_rows,
                     rejected_rows, started_at)
                VALUES (gen_random_uuid(), 'X', 'SANCTIONS', 'h1', 'FAILED', 0, 0, 0, now())
                """)).hasMessageContaining("chk_wv_failure");
    }

    @Test
    @DisplayName("el nombre se guarda normalizado y también ordenado")
    void namesArePersistedNormalizedAndSorted() {
        loads.load("OFAC-SDN", WatchlistKind.SANCTIONS, LocalDate.of(2026, 9, 1));

        // Ni acentos ni minúsculas: si alguno sobrevive, la normalización dejó de correr al cargar
        // y el cruce dependería de que cada consulta la repita igual.
        Integer conAcentos = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM compliance.watchlist_entries WHERE normalized_name ~ '[^A-Z0-9 ]'",
                Integer.class);
        assertThat(conAcentos).isZero();

        Integer sinOrdenar = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM compliance.watchlist_entries WHERE sorted_name = ''", Integer.class);
        assertThat(sinOrdenar).isZero();
    }
}

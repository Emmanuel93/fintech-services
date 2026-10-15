package com.fintech.compliance;

import com.fintech.compliance.application.service.ScreeningService;
import com.fintech.compliance.application.service.WatchlistLoadService;
import com.fintech.compliance.domain.*;
import com.fintech.shared.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El cruce, contra Postgres de verdad.
 *
 * <p>{@link ScreeningTest} prueba la lógica con un repositorio en memoria y es donde viven los casos
 * de negocio. Esto prueba lo que aquél <b>no puede</b>: que las consultas funcionen. El parecido es
 * SQL nativo con {@code similarity()} de `pg_trgm` y un {@code IN (:versions)} — cosas que compilan
 * perfectamente y fallan al ejecutarse, y que ninguna prueba con dobles detectaría.
 */
@SpringBootTest
class ScreeningAgainstPostgresIT extends AbstractIntegrationTest {

    @Override protected List<String> schemas() { return List.of("compliance"); }

    @Autowired ScreeningService screening;
    @Autowired WatchlistLoadService loads;

    /** Siembra una entrada a mano en una versión ACTIVE, para controlar exactamente contra qué se cruza. */
    private UUID seedList(WatchlistKind kind, String name, String curp,
                          LocalDate dob, String country) {
        UUID versionId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO compliance.watchlist_versions
                    (version_id, source_code, kind, file_hash, status, total_rows, accepted_rows,
                     rejected_rows, started_at, activated_at)
                VALUES (?, ?, ?, ?, 'ACTIVE', 1, 1, 0, now(), now())
                """, versionId, "TEST-" + UUID.randomUUID().toString().substring(0, 8),
                kind.name(), UUID.randomUUID().toString());

        // La fila se arma con la fábrica del dominio y luego se inserta, en vez de escribir los
        // campos a mano: así las llaves derivadas —las dos raíces de CURP— salen de la MISMA regla
        // que en producción. Repetir la derivación aquí haría que la prueba pudiera pasar con una
        // regla distinta de la que corre de verdad, que es justo lo que no debe poder ocurrir.
        NormalizedName n = NormalizedName.of(name);
        WatchlistEntry e = WatchlistEntry.of(versionId, kind, name, n, curp, null, dob, country, "TEST", 2);

        jdbcTemplate.update("""
                INSERT INTO compliance.watchlist_entries
                    (entry_id, version_id, kind, raw_name, normalized_name, sorted_name,
                     curp, rfc, date_of_birth, country, program, source_line,
                     curp_root_a, curp_root_b)
                VALUES (?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, 'TEST', 2, ?, ?)
                """, e.getEntryId(), versionId, kind.name(), name, n.value(), n.sorted(),
                curp, dob, country, e.getCurpRootA(), e.getCurpRootB());
        return versionId;
    }

    private ScreeningSubject person(String name, LocalDate dob, String curp, String country) {
        return new ScreeningSubject(UUID.randomUUID(), name, dob, curp, null, country);
    }

    @Test
    @DisplayName("la consulta por nombre ordenado encuentra a la persona escrita al revés")
    void theSortedNameQueryWorks() {
        seedList(WatchlistKind.SANCTIONS, "PÉREZ GARCÍA JUAN", null, null, "MX");

        var out = screening.screen(person("Juan Perez Garcia", null, null, "MX"));

        assertThat(out.status()).isEqualTo(ScreeningStatus.POTENTIAL_MATCH);
        assertThat(out.candidates().get(0).reason()).isEqualTo(MatchReason.NAME_EXACT);
    }

    @Test
    @DisplayName("la consulta por documento encuentra por CURP sin importar mayúsculas")
    void theDocumentQueryWorks() {
        seedList(WatchlistKind.SANCTIONS, "J PEREZ", "PEGJ850312HDFXXX01", null, null);

        var out = screening.screen(person("OTRO NOMBRE", null, "pegj850312hdfxxx01", "MX"));

        assertThat(out.candidates()).singleElement()
                .satisfies(c -> assertThat(c.reason()).isEqualTo(MatchReason.DOCUMENT_IDENTICAL));
    }

    @Test
    @DisplayName("el parecido de pg_trgm encuentra «GONZALES» buscando «GONZÁLEZ»")
    void theTrigramQueryWorks() {
        // Es el caso que justifica tener búsqueda difusa: transliteraciones y errores de captura.
        // Y es SQL nativo, así que sólo corriéndolo se sabe si funciona.
        seedList(WatchlistKind.SANCTIONS, "MARIA GONZALES LOPEZ", null, null, "MX");

        var out = screening.screen(person("María Gonzáles López", null, null, "MX"));

        assertThat(out.candidates()).isNotEmpty();
        assertThat(out.status()).isEqualTo(ScreeningStatus.POTENTIAL_MATCH);
    }

    @Test
    @DisplayName("una persona que no se parece a nadie sale CLEARED")
    void someoneUnrelatedIsCleared() {
        seedList(WatchlistKind.SANCTIONS, "IVAN PETROV", null, null, "RU");

        var out = screening.screen(person("JUAN PEREZ GARCIA", LocalDate.of(1985, 3, 12), null, "MX"));

        assertThat(out.status()).isEqualTo(ScreeningStatus.CLEARED);
    }

    @Test
    @DisplayName("sólo se cruza contra versiones ACTIVE: una jubilada no decide sobre nadie")
    void onlyActiveVersionsAreSearched() {
        // Es el invariante de la carga versionada visto desde el otro lado: si una versión jubilada
        // siguiera cruzando, activar una lista nueva no quitaría a quien ya salió de la anterior.
        UUID versionId = seedList(WatchlistKind.SANCTIONS, "JUAN PEREZ GARCIA", null, null, "MX");
        jdbcTemplate.update(
                "UPDATE compliance.watchlist_versions SET status='SUPERSEDED' WHERE version_id=?", versionId);

        var out = screening.screen(person("JUAN PEREZ GARCIA", null, null, "MX"));

        assertThat(out.status())
                .as("sin ninguna versión vigente el veredicto es PENDING, nunca CLEARED")
                .isEqualTo(ScreeningStatus.PENDING);
    }

    @Test
    @DisplayName("sobre una lista cargada de verdad por el mock, el cruce corre de punta a punta")
    void endToEndOverARealLoad() {
        loads.load("OFAC-SDN", WatchlistKind.SANCTIONS, LocalDate.of(2026, 9, 1));

        String unNombre = jdbcTemplate.queryForObject(
                "SELECT raw_name FROM compliance.watchlist_entries LIMIT 1", String.class);

        var out = screening.screen(person(unNombre, null, null, "MX"));

        assertThat(out.status()).isEqualTo(ScreeningStatus.POTENTIAL_MATCH);
        assertThat(out.listVersions()).hasSize(1);
        assertThat(out.summary()).contains("candidato");
    }
    // ── La raíz del CURP, contra las dos columnas de verdad ──────────────────────────────────

    @Test
    @DisplayName("la consulta por raíz encuentra a quien la lista publicó SIN CURP")
    void therootQueryFindsAnEntryWithoutACurp() {
        // El caso que la derivación existe para cubrir: la lista no trae CURP —medido, la mayoría no
        // lo trae— así que el cruce por documento no puede encontrar nada. La raíz sí.
        //
        // Y el nombre está escrito distinto, de modo que el cruce por nombre exacto tampoco alcanza:
        // lo único que puede encontrarla es la consulta nueva.
        seedList(WatchlistKind.SANCTIONS, "GONZALES LOPEZ MARIA", null, LocalDate.of(1985, 3, 12), "MX");

        var out = screening.screen(person("GONZÁLEZ LÓPEZ MARÍA",
                LocalDate.of(1985, 3, 12), "GOLM850312MDFNPR07", "MX"));

        assertThat(out.status()).isEqualTo(ScreeningStatus.POTENTIAL_MATCH);
        assertThat(out.candidates()).anySatisfy(c ->
                assertThat(c.reason()).isEqualTo(MatchReason.CURP_ROOT));
    }

    @Test
    @DisplayName("y la encuentra también cuando la lista escribió el nombre al revés")
    void therootQueryWorksOnTheSecondColumn() {
        // Ésta cae por la SEGUNDA columna —la lectura «NOMBRE APELLIDO APELLIDO»— y por eso está
        // separada: la consulta pregunta por las dos con un `OR`, y un `OR` que sólo se prueba por
        // un lado deja la otra mitad sin verificar.
        seedList(WatchlistKind.SANCTIONS, "MARIA GONZALES LOPEZ", null, LocalDate.of(1985, 3, 12), "MX");

        var out = screening.screen(person("GONZÁLEZ LÓPEZ MARÍA",
                LocalDate.of(1985, 3, 12), "GOLM850312MDFNPR07", "MX"));

        assertThat(out.candidates()).anySatisfy(c ->
                assertThat(c.reason()).isEqualTo(MatchReason.CURP_ROOT));
    }

    @Test
    @DisplayName("una raíz que no coincide no trae a nadie: la consulta filtra, no arrastra")
    void therootQueryDoesNotDragEveryone() {
        // Una consulta mal escrita sobre dos columnas nulas puede devolver la lista entera. Con una
        // persona sin nada en común, el resultado tiene que ser exactamente cero.
        seedList(WatchlistKind.SANCTIONS, "GONZALES LOPEZ MARIA", null, LocalDate.of(1985, 3, 12), "MX");

        var out = screening.screen(person("IVÁN PETROV", LocalDate.of(1970, 7, 7), "PEIV700707HNERVN04", "RU"));

        assertThat(out.status()).isEqualTo(ScreeningStatus.CLEARED);
    }

}

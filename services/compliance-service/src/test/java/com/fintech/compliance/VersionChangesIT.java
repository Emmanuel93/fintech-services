package com.fintech.compliance;

import com.fintech.compliance.application.port.out.VersionChangeRepository;
import com.fintech.compliance.application.service.WatchlistLoadService;
import com.fintech.compliance.domain.WatchlistKind;
import com.fintech.compliance.domain.WatchlistVersionStatus;
import com.fintech.shared.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El detalle de qué cambió en cada actualización de una lista.
 *
 * <p>Es el rastro <b>de la lista</b>, distinto del rastro de la persona: el veredicto dice qué se
 * decidió sobre alguien; esto dice qué decía la fuente y desde cuándo. Sin él, «¿por qué esta
 * persona quedó señalada el día 14?» sólo se contesta comparando dos publicaciones de cientos de
 * miles de filas a mano.
 *
 * <p>Va contra Postgres real porque el diff es <b>SQL nativo</b> con operaciones de conjunto: compila
 * perfectamente y sólo ejecutándolo se sabe si funciona.
 */
@SpringBootTest
class VersionChangesIT extends AbstractIntegrationTest {

    @Override protected List<String> schemas() { return List.of("compliance"); }

    @Autowired WatchlistLoadService loads;
    @Autowired VersionChangeRepository changes;

    /** Un publicador que entrega exactamente el CSV que la prueba le dé. */
    private WatchlistLoadService.LoadResult publish(String sourceCode, LocalDate day, String... names) {
        StringBuilder csv = new StringBuilder("NOMBRE,CURP,RFC,FECHA_NACIMIENTO,PAIS,PROGRAMA\n");
        for (String n : names) csv.append(n).append("\n");

        var port = new com.fintech.compliance.application.port.out.GovernmentListPort() {
            @Override public com.fintech.compliance.domain.CsvLayout layoutFor(String s) {
                return new com.fintech.compliance.domain.CsvLayout(',',
                        java.nio.charset.StandardCharsets.UTF_8,
                        Map.of("name", "NOMBRE", "curp", "CURP", "rfc", "RFC",
                               "dateOfBirth", "FECHA_NACIMIENTO", "country", "PAIS", "program", "PROGRAMA"),
                        List.of("yyyy-MM-dd"));
            }
            @Override public java.io.InputStream download(String s, WatchlistKind k, LocalDate d) {
                return new java.io.ByteArrayInputStream(
                        csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            @Override public String describeProvider() { return "TEST"; }
        };
        return new WatchlistLoadService(port,
                applicationContextVersions(), applicationContextEntries(),
                applicationContextRejections(), changes).load(sourceCode, WatchlistKind.SANCTIONS, day);
    }

    @Autowired com.fintech.compliance.application.port.out.WatchlistVersionRepository versionsBean;
    @Autowired com.fintech.compliance.application.port.out.WatchlistEntryRepository entriesBean;
    @Autowired com.fintech.compliance.application.port.out.RowRejectionRepository rejectionsBean;

    private com.fintech.compliance.application.port.out.WatchlistVersionRepository applicationContextVersions() { return versionsBean; }
    private com.fintech.compliance.application.port.out.WatchlistEntryRepository applicationContextEntries() { return entriesBean; }
    private com.fintech.compliance.application.port.out.RowRejectionRepository applicationContextRejections() { return rejectionsBean; }

    private UUID activeVersionOf(String sourceCode) {
        return jdbcTemplate.queryForObject(
                "SELECT version_id FROM compliance.watchlist_versions WHERE source_code=? AND status='ACTIVE'",
                UUID.class, sourceCode);
    }

    // ── La línea base ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("la PRIMERA carga no se detalla entrada por entrada: es la línea base")
    void thefirstLoadIsRecordedAsABaseline() {
        // Detallarla serían cientos de miles de filas diciendo «todo es nuevo». Lo que un revisor
        // mira son los cambios contra ella.
        var r = publish("DIFF-A", LocalDate.of(2026, 9, 1),
                "JUAN PEREZ,,,1985-03-12,MX,SDN",
                "ANA LOPEZ,,,1975-02-02,MX,SDN");
        assertThat(r.activated()).isTrue();

        UUID v1 = activeVersionOf("DIFF-A");
        assertThat(changes.countByType(v1)).containsEntry("INITIAL_LOAD", 1L);
        assertThat(changes.countByType(v1)).doesNotContainKey("ADDED");
    }

    // ── Los tres tipos de cambio ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("una actualización distingue altas, bajas y modificadas")
    void anUpdateDistinguishesAddedRemovedAndModified() {
        publish("DIFF-B", LocalDate.of(2026, 9, 1),
                "JUAN PEREZ,,,1985-03-12,MX,SDN",       // se queda igual
                "ANA LOPEZ,,,1975-02-02,MX,SDN",        // se va
                "ROSA MARTINEZ,,,1990-05-05,MX,SDN");   // le cambia el programa

        publish("DIFF-B", LocalDate.of(2026, 9, 8),
                "JUAN PEREZ,,,1985-03-12,MX,SDN",
                "ROSA MARTINEZ,,,1990-05-05,MX,NON-SDN",
                "MIGUEL TORRES,,,1980-01-01,MX,SDN");   // entra

        UUID v2 = activeVersionOf("DIFF-B");
        Map<String, Long> resumen = changes.countByType(v2);

        assertThat(resumen).containsEntry("ADDED", 1L);      // MIGUEL
        assertThat(resumen).containsEntry("REMOVED", 1L);    // ANA
        assertThat(resumen).containsEntry("MODIFIED", 1L);   // ROSA cambió de programa
        assertThat(resumen).doesNotContainKey("INITIAL_LOAD");
    }

    @Test
    @DisplayName("el alta dice QUIÉN entró, que es lo que contesta «por qué quedó señalado»")
    void anAdditionNamesWhoEntered() {
        publish("DIFF-C", LocalDate.of(2026, 9, 1), "JUAN PEREZ,,,1985-03-12,MX,SDN");
        publish("DIFF-C", LocalDate.of(2026, 9, 8),
                "JUAN PEREZ,,,1985-03-12,MX,SDN",
                "MIGUEL TORRES,,,1980-01-01,MX,SDN");

        UUID v2 = activeVersionOf("DIFF-C");
        var altas = changes.findByVersion(v2, "ADDED", 10);

        assertThat(altas).singleElement().satisfies(c -> {
            assertThat(c.getListedName()).isEqualTo("MIGUEL TORRES");
            assertThat(c.getProgram()).isEqualTo("SDN");
            assertThat(c.getPreviousVersionId()).isNotNull();
        });
    }

    @Test
    @DisplayName("una entrada corregida sale MODIFIED, no como una baja y un alta")
    void acorrectedEntryIsModifiedNotRemovedAndAdded() {
        // Es el punto de tener dos llaves: la identidad —nombre y documentos— aparte del contenido.
        // Comparando el contenido entero, corregir una fecha de nacimiento se vería como que una
        // persona salió de la lista y otra distinta entró, que es justo lo contrario de lo ocurrido.
        publish("DIFF-D", LocalDate.of(2026, 9, 1), "JUAN PEREZ,,,1985-03-12,MX,SDN");
        publish("DIFF-D", LocalDate.of(2026, 9, 8), "JUAN PEREZ,,,1962-01-01,MX,SDN");

        UUID v2 = activeVersionOf("DIFF-D");
        Map<String, Long> resumen = changes.countByType(v2);

        assertThat(resumen).containsEntry("MODIFIED", 1L);
        assertThat(resumen).doesNotContainKey("ADDED");
        assertThat(resumen).doesNotContainKey("REMOVED");
    }

    @Test
    @DisplayName("una actualización sin cambios no registra nada")
    void anIdenticalUpdateRecordsNothing() {
        // No puede pasar por el mismo archivo —la idempotencia por hash lo impide— pero sí con un
        // archivo distinto que dice lo mismo: otro orden de filas, otra fecha en la cabecera.
        publish("DIFF-E", LocalDate.of(2026, 9, 1),
                "JUAN PEREZ,,,1985-03-12,MX,SDN", "ANA LOPEZ,,,1975-02-02,MX,SDN");
        publish("DIFF-E", LocalDate.of(2026, 9, 8),
                "ANA LOPEZ,,,1975-02-02,MX,SDN", "JUAN PEREZ,,,1985-03-12,MX,SDN");

        UUID v2 = activeVersionOf("DIFF-E");
        assertThat(changes.countByType(v2)).isEmpty();
    }

    // ── Y la anterior sigue viva hasta el último momento ─────────────────────────────────────

    @Test
    @DisplayName("mientras la nueva carga, la anterior sigue siendo la vigente")
    void thepreviousStaysActiveUntilTheNewOneIsReady() {
        publish("DIFF-F", LocalDate.of(2026, 9, 1), "JUAN PEREZ,,,1985-03-12,MX,SDN");
        UUID v1 = activeVersionOf("DIFF-F");

        publish("DIFF-F", LocalDate.of(2026, 9, 8), "MIGUEL TORRES,,,1980-01-01,MX,SDN");
        UUID v2 = activeVersionOf("DIFF-F");

        assertThat(v2).isNotEqualTo(v1);

        // Y la anterior no se borra: un veredicto emitido ayer se explica con la lista de ayer.
        String estadoVieja = jdbcTemplate.queryForObject(
                "SELECT status FROM compliance.watchlist_versions WHERE version_id=?", String.class, v1);
        assertThat(estadoVieja).isEqualTo(WatchlistVersionStatus.SUPERSEDED.name());

        Integer filasViejas = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM compliance.watchlist_entries WHERE version_id=?", Integer.class, v1);
        assertThat(filasViejas).isEqualTo(1);
    }
}

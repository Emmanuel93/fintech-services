package com.fintech.compliance;

import com.fintech.compliance.application.port.out.*;
import com.fintech.compliance.application.service.WatchlistLoadService;
import com.fintech.compliance.domain.*;
import com.fintech.compliance.infrastructure.adapter.out.government.MockGovernmentListAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Cargar una lista bien: versionada, idempotente, y que no se active si dejó de ser la misma lista.
 *
 * <p>Las cuatro promesas que protegen estas pruebas, y qué pasaría sin cada una:
 *
 * <ul>
 *   <li><b>Versionar en vez de mutar</b> — sin esto, un fallo a mitad de archivo deja una ventana en
 *       la que quien todavía no se insertó sale limpio, y esa ventana no deja rastro.</li>
 *   <li><b>Idempotencia por hash</b> — sin esto, cada reintento de sincronización duplica la lista
 *       entera.</li>
 *   <li><b>Cero filas buenas es un fallo</b> — sin esto, un layout cambiado activa una lista vacía y
 *       todo el mundo pasa.</li>
 *   <li><b>El salto de rechazo frena la activación</b> — sin esto, un archivo que dejó de ser
 *       legible se activa a medias y deja fuera a quien sí estaba.</li>
 * </ul>
 */
class WatchlistLoadingTest {

    private static final LocalDate DIA = LocalDate.of(2026, 9, 1);

    // ── Repositorios en memoria ──────────────────────────────────────────────────────────────

    private static final class Versions implements WatchlistVersionRepository {
        final Map<UUID, WatchlistVersion> rows = new LinkedHashMap<>();

        @Override public Optional<WatchlistVersion> findByFileHash(String h) {
            return rows.values().stream().filter(v -> v.getFileHash().equals(h)).findFirst();
        }
        @Override public Optional<WatchlistVersion> findBySourceCodeAndStatus(String code, WatchlistVersionStatus st) {
            return rows.values().stream()
                    .filter(v -> v.getSourceCode().equals(code) && v.getStatus() == st).findFirst();
        }
        @Override public Optional<WatchlistVersion> findLastFinished(String code) {
            return rows.values().stream()
                    .filter(v -> v.getSourceCode().equals(code))
                    .filter(v -> v.getStatus() == WatchlistVersionStatus.ACTIVE
                              || v.getStatus() == WatchlistVersionStatus.SUPERSEDED
                              || v.getStatus() == WatchlistVersionStatus.LOADED)
                    .reduce((a, b) -> b);
        }
        @Override public WatchlistVersion save(WatchlistVersion v) { rows.put(v.getVersionId(), v); return v; }
        @Override public Optional<WatchlistVersion> findLastActivatedAfter(java.time.Instant since) {
            return rows.values().stream()
                    .filter(v -> v.getStatus() == WatchlistVersionStatus.ACTIVE)
                    .filter(v -> v.getActivatedAt() != null && v.getActivatedAt().isAfter(since))
                    .reduce((a, b) -> b);
        }
    }

    private static final class Entries implements WatchlistEntryRepository {
        final List<WatchlistEntry> rows = new ArrayList<>();
        @Override public void saveAll(List<WatchlistEntry> b) { rows.addAll(b); }
        @Override public long countByVersionId(UUID v) { return rows.stream().filter(e -> e.getVersionId().equals(v)).count(); }
        @Override public void deleteByVersionId(UUID v) { rows.removeIf(e -> e.getVersionId().equals(v)); }
    }

    private static final class Rejections implements RowRejectionRepository {
        final List<RowRejection> rows = new ArrayList<>();
        @Override public void saveAll(List<RowRejection> b) { rows.addAll(b); }
        @Override public List<RowRejection> findByVersionId(UUID v, int limit) {
            return rows.stream().filter(r -> r.getVersionId().equals(v)).limit(limit).toList();
        }
    }

    /** Un publicador que entrega exactamente el texto que el test le dé. */
    private static final class FixedList implements GovernmentListPort {
        String csv;
        FixedList(String csv) { this.csv = csv; }
        @Override public CsvLayout layoutFor(String s) {
            return new CsvLayout(',', StandardCharsets.UTF_8,
                    Map.of(CsvLayout.COL_NAME, "NOMBRE", CsvLayout.COL_CURP, "CURP"), List.of("yyyy-MM-dd"));
        }
        @Override public InputStream download(String s, WatchlistKind k, LocalDate d) {
            return new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8));
        }
        @Override public String describeProvider() { return "FIXED(test)"; }
    }

    private Versions versions;
    private Entries entries;
    private Rejections rejections;

    @BeforeEach
    void setUp() {
        versions   = new Versions();
        entries    = new Entries();
        rejections = new Rejections();
        changes    = new Changes();
    }

    /** El detalle de la actualización, en memoria: qué se añadió, qué se quitó, qué cambió. */
    private static final class Changes implements VersionChangeRepository {
        final List<String> registrados = new ArrayList<>();
        @Override public int computeAndStore(UUID nueva, UUID vieja) {
            registrados.add("DIFF " + nueva + " vs " + vieja); return 0;
        }
        @Override public void recordInitialLoad(WatchlistVersionChange initial) {
            registrados.add("INITIAL " + initial.getVersionId());
        }
        @Override public List<WatchlistVersionChange> findByVersion(UUID v, String t, int l) { return List.of(); }
        @Override public Map<String, Long> countByType(UUID v) { return Map.of(); }
    }

    private Changes changes;

    private WatchlistLoadService serviceWith(GovernmentListPort port) {
        if (changes == null) changes = new Changes();
        return new WatchlistLoadService(port, versions, entries, rejections, changes);
    }

    // ── Versionar y activar ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("una carga limpia queda ACTIVA y con sus filas")
    void aCleanLoadBecomesActive() {
        var result = serviceWith(new FixedList("""
                NOMBRE,CURP
                JUAN PEREZ,PEJU800101HDFXXX01
                ANA LOPEZ,LOAN750202MDFXXX02
                """)).load("OFAC-SDN", WatchlistKind.SANCTIONS, DIA);

        assertThat(result.activated()).isTrue();
        assertThat(result.acceptedRows()).isEqualTo(2);
        assertThat(entries.rows).hasSize(2);
    }

    @Test
    @DisplayName("activar una versión nueva jubila la anterior: nunca hay dos vigentes")
    void activatingSupersedesThePrevious() {
        serviceWith(new FixedList("NOMBRE,CURP\nJUAN PEREZ,\n"))
                .load("OFAC-SDN", WatchlistKind.SANCTIONS, DIA);
        serviceWith(new FixedList("NOMBRE,CURP\nJUAN PEREZ,\nANA LOPEZ,\n"))
                .load("OFAC-SDN", WatchlistKind.SANCTIONS, DIA.plusDays(1));

        var porEstado = versions.rows.values().stream()
                .collect(java.util.stream.Collectors.groupingBy(WatchlistVersion::getStatus,
                        java.util.stream.Collectors.counting()));

        assertThat(porEstado.get(WatchlistVersionStatus.ACTIVE)).isEqualTo(1);
        assertThat(porEstado.get(WatchlistVersionStatus.SUPERSEDED)).isEqualTo(1);
    }

    @Test
    @DisplayName("la versión jubilada NO se borra: un veredicto de ayer se explica con la lista de ayer")
    void thePreviousVersionIsKept() {
        serviceWith(new FixedList("NOMBRE,CURP\nJUAN PEREZ,\n"))
                .load("OFAC-SDN", WatchlistKind.SANCTIONS, DIA);
        serviceWith(new FixedList("NOMBRE,CURP\nANA LOPEZ,\n"))
                .load("OFAC-SDN", WatchlistKind.SANCTIONS, DIA.plusDays(1));

        assertThat(versions.rows).hasSize(2);
        assertThat(entries.rows).hasSize(2);   // las filas de la vieja siguen ahí
    }

    // ── Idempotencia ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("el mismo archivo dos veces no carga nada la segunda")
    void thesameFileTwiceIsANoOp() {
        String csv = "NOMBRE,CURP\nJUAN PEREZ,PEJU800101HDFXXX01\n";

        var primera = serviceWith(new FixedList(csv)).load("OFAC-SDN", WatchlistKind.SANCTIONS, DIA);
        var segunda = serviceWith(new FixedList(csv)).load("OFAC-SDN", WatchlistKind.SANCTIONS, DIA.plusDays(3));

        assertThat(primera.activated()).isTrue();
        assertThat(segunda.skipped()).isTrue();
        assertThat(versions.rows).hasSize(1);
        assertThat(entries.rows).hasSize(1);   // no se duplicó la lista
    }

    @Test
    @DisplayName("la idempotencia es por CONTENIDO, no por nombre ni por fecha")
    void idempotencyIsByContent() {
        // Los publicadores renombran y refechan archivos sin cambiar lo que dicen.
        String csv = "NOMBRE,CURP\nJUAN PEREZ,\n";
        serviceWith(new FixedList(csv)).load("OFAC-SDN", WatchlistKind.SANCTIONS, DIA);
        var otraFecha = serviceWith(new FixedList(csv)).load("OFAC-SDN", WatchlistKind.SANCTIONS, DIA.plusMonths(2));

        assertThat(otraFecha.skipped()).isTrue();
    }

    // ── Cero filas buenas no es una lista vacía ──────────────────────────────────────────────

    @Test
    @DisplayName("un archivo sin ninguna fila válida FALLA — no se activa una lista vacía")
    void anEmptyLoadFails() {
        // Una lista de sancionados vacía no existe: si lo parece, fue la descarga o el layout.
        // Activarla dejaría pasar a todo el mundo, y nada lo indicaría.
        assertThatThrownBy(() -> serviceWith(new FixedList("NOMBRE,CURP\n---,\n,\n"))
                .load("OFAC-SDN", WatchlistKind.SANCTIONS, DIA))
                .isInstanceOf(EmptyWatchlistLoadException.class)
                .hasMessageContaining("una lista vacía no se activa");

        assertThat(versions.rows.values())
                .noneMatch(v -> v.getStatus() == WatchlistVersionStatus.ACTIVE);
    }

    @Test
    @DisplayName("si la carga falla, la lista vigente sigue siendo la anterior")
    void aFailedLoadLeavesThePreviousActive() {
        serviceWith(new FixedList("NOMBRE,CURP\nJUAN PEREZ,\n"))
                .load("OFAC-SDN", WatchlistKind.SANCTIONS, DIA);

        try {
            serviceWith(new FixedList("NOMBRE,CURP\n---,\n"))
                    .load("OFAC-SDN", WatchlistKind.SANCTIONS, DIA.plusDays(1));
        } catch (EmptyWatchlistLoadException expected) { /* es el punto */ }

        assertThat(versions.rows.values())
                .filteredOn(v -> v.getStatus() == WatchlistVersionStatus.ACTIVE)
                .singleElement()
                .satisfies(v -> assertThat(v.getPublishedOn()).isEqualTo(DIA));
    }

    // ── El freno: el layout cambió ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("si la tasa de rechazo se dispara, la versión NO se activa")
    void aJumpInRejectionsBlocksActivation() {
        // Carga limpia: 0 % de rechazo.
        serviceWith(new FixedList("""
                NOMBRE,CURP
                JUAN PEREZ,
                ANA LOPEZ,
                ROSA MARTINEZ,
                MIGUEL TORRES,
                """)).load("OFAC-SDN", WatchlistKind.SANCTIONS, DIA);

        // La siguiente trae la mitad ilegible: eso no es una lista peor, es otra lista.
        var segunda = serviceWith(new FixedList("""
                NOMBRE,CURP
                JUAN PEREZ,
                ---,
                ---,
                ---,
                """)).load("OFAC-SDN", WatchlistKind.SANCTIONS, DIA.plusDays(1));

        assertThat(segunda.activated()).isFalse();
        assertThat(segunda.detail()).contains("el layout cambió");
        assertThat(segunda.status()).isEqualTo(WatchlistVersionStatus.LOADED);

        // Y la vigente sigue siendo la primera: el freno actúa ANTES de decidir sobre personas.
        assertThat(versions.rows.values())
                .filteredOn(v -> v.getStatus() == WatchlistVersionStatus.ACTIVE)
                .singleElement()
                .satisfies(v -> assertThat(v.getPublishedOn()).isEqualTo(DIA));
    }

    @Test
    @DisplayName("una lista que SIEMPRE viene sucia no se frena: lo que delata es el salto, no el nivel")
    void aConsistentlyDirtyListIsNotBlocked() {
        String sucio = """
                NOMBRE,CURP
                JUAN PEREZ,
                ANA LOPEZ,
                ---,
                ---,
                """;
        serviceWith(new FixedList(sucio)).load("UIF-PLD", WatchlistKind.SANCTIONS, DIA);

        var segunda = serviceWith(new FixedList("""
                NOMBRE,CURP
                ROSA MARTINEZ,
                MIGUEL TORRES,
                ---,
                ---,
                """)).load("UIF-PLD", WatchlistKind.SANCTIONS, DIA.plusDays(1));

        assertThat(segunda.activated())
                .as("50 % de rechazo en las dos: el nivel es alto pero no cambió nada")
                .isTrue();
    }

    // ── Los rechazos se guardan, no sólo se cuentan ──────────────────────────────────────────

    @Test
    @DisplayName("cada rechazo guarda su línea y su motivo, que es lo que permite arreglarlo")
    void rejectionsAreKeptWithTheirLine() {
        serviceWith(new FixedList("""
                NOMBRE,CURP
                JUAN PEREZ,
                ,SINNOMBRE
                ANA LOPEZ,
                """)).load("OFAC-SDN", WatchlistKind.SANCTIONS, DIA);

        assertThat(rejections.rows).singleElement().satisfies(r -> {
            assertThat(r.getSourceLine()).isEqualTo(3);
            assertThat(r.getReason()).contains("sin nombre");
            assertThat(r.getRawLine()).isNotBlank();
        });
    }

    // ── El mock, que es lo que hoy alimenta todo esto ────────────────────────────────────────

    @Test
    @DisplayName("el mock entrega un archivo sucio y la carga lo sobrevive")
    void theMockIsDirtyAndTheLoadSurvivesIt() {
        var result = serviceWith(new MockGovernmentListAdapter())
                .load("OFAC-SDN", WatchlistKind.SANCTIONS, DIA);

        assertThat(result.activated()).isTrue();
        assertThat(result.acceptedRows()).isGreaterThan(20);
        assertThat(result.rejectedRows())
                .as("un mock que entregara un CSV impecable no probaría nada")
                .isGreaterThan(0);
    }

    @Test
    @DisplayName("el mock es determinista: misma lista y misma fecha ⇒ mismo archivo ⇒ no recarga")
    void theMockIsDeterministic() {
        // Es lo que hace comprobable la idempotencia: sin determinismo, cada llamada daría otro
        // hash y la segunda carga siempre parecería nueva.
        var primera = serviceWith(new MockGovernmentListAdapter())
                .load("PEP-MX", WatchlistKind.PEP, DIA);
        var segunda = serviceWith(new MockGovernmentListAdapter())
                .load("PEP-MX", WatchlistKind.PEP, DIA);

        assertThat(primera.fileHash()).isEqualTo(segunda.fileHash());
        assertThat(segunda.skipped()).isTrue();
    }

    @Test
    @DisplayName("cada lista trae su propio layout — separador y encoding distintos")
    void eachListHasItsOwnLayout() {
        var mock = new MockGovernmentListAdapter();

        assertThat(mock.layoutFor("SAT-69B").delimiter()).isEqualTo('|');
        assertThat(mock.layoutFor("SAT-69B").charset()).isEqualTo(StandardCharsets.ISO_8859_1);
        assertThat(mock.layoutFor("PEP-MX").delimiter()).isEqualTo(',');
        assertThat(mock.layoutFor("PEP-MX").charset()).isEqualTo(StandardCharsets.UTF_8);

        // Y las tres cargan, que es la prueba de que el layout como dato funciona.
        assertThat(serviceWith(mock).load("SAT-69B", WatchlistKind.TAX_BLACKLIST, DIA).activated()).isTrue();
    }
}

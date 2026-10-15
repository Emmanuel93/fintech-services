package com.fintech.compliance;

import com.fintech.compliance.application.port.out.*;
import com.fintech.compliance.application.service.RescreenService;
import com.fintech.compliance.application.service.ScreeningService;
import com.fintech.compliance.domain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * El re-cruce: volver a mirar a la población viva cuando cambian las listas.
 *
 * <h2>Sin esto, «AML en cada disposición» sería falso</h2>
 *
 * El veredicto se calcula al alta y, sin re-cruce, no se vuelve a tocar nunca. La compuerta de la
 * disposición leería para siempre un dictamen emitido contra listas de hace años — el control
 * caducaría al día siguiente de dar de alta a la persona, sin que nada lo indicara.
 *
 * <h2>Y los veredictos NO se invalidan mientras corre</h2>
 *
 * Es la decisión del negocio: se asume la ventana en vez de detener todos los desembolsos. Estas
 * pruebas la fijan —para que nadie la «arregle» sin saber que era deliberada— y fijan también lo
 * que la hace defendible: que la corrida quede registrada con su duración.
 */
class RescreenTest {

    private static final UUID VERSION = UUID.randomUUID();

    // ── Dobles ───────────────────────────────────────────────────────────────────────────────

    private static final class Repo implements PersonVerificationRepository {
        final List<PersonVerification> rows = new ArrayList<>();
        @Override public Optional<PersonVerification> findByProspectId(UUID p) {
            return rows.stream().filter(v -> v.getProspectId().equals(p)).findFirst();
        }
        @Override public Optional<PersonVerification> findByPartyId(UUID p) {
            return rows.stream().filter(v -> p.equals(v.getPartyId())).findFirst();
        }
        @Override public Optional<PersonVerification> findById(UUID id) {
            return rows.stream().filter(v -> v.getVerificationId().equals(id)).findFirst();
        }
        @Override public PersonVerification save(PersonVerification v) {
            if (rows.stream().noneMatch(x -> x.getVerificationId().equals(v.getVerificationId()))) rows.add(v);
            return v;
        }
        @Override public List<PersonVerification> findPage(int page, int size) {
            int from = page * size;
            return from >= rows.size() ? List.of() : rows.subList(from, Math.min(rows.size(), from + size));
        }
    }

    private static final class Runs implements RescreenRunRepository {
        final Map<UUID, RescreenRun> rows = new LinkedHashMap<>();
        @Override public RescreenRun save(RescreenRun r) { rows.put(r.getRunId(), r); return r; }
        @Override public List<RescreenRun> findRecent(int limit) { return List.copyOf(rows.values()); }
        @Override public Optional<RescreenRun> findById(UUID id) { return Optional.ofNullable(rows.get(id)); }
    }

    private static final class Publisher implements VerificationEventPublisher {
        final List<UUID> publicados = new ArrayList<>();
        @Override public void publishVerificationCompleted(PersonVerification v) { publicados.add(v.getPartyId()); }
        @Override public void publishConfirmedMatch(PersonVerification v, String n, String p) { }
    }

    private static class Lists implements WatchlistSearchPort {
        final List<WatchlistEntry> rows = new ArrayList<>();
        @Override public Set<UUID> activeVersionIds() { return Set.of(VERSION); }
        @Override public List<WatchlistEntry> findByDocument(String c, String r, Set<UUID> v) { return List.of(); }
        @Override public List<WatchlistEntry> findByCurpRoot(Set<String> roots, Set<UUID> v) {
            return rows.stream().filter(e -> !java.util.Collections.disjoint(e.curpRoots(), roots)).toList();
        }
        @Override public List<WatchlistEntry> findBySortedName(String s, Set<UUID> v) {
            return rows.stream().filter(e -> s.equals(e.getSortedName())).toList();
        }
        @Override public List<ScoredEntry> findSimilarNames(String n, double m, Set<UUID> v) { return List.of(); }

        Lists publish(String name) {
            rows.add(WatchlistEntry.of(VERSION, WatchlistKind.SANCTIONS, name,
                    NormalizedName.of(name), null, null, null, null, "SDN", 2));
            return this;
        }
    }

    private Repo repo;
    private Runs runs;
    private Publisher publisher;
    private PersonVerificationTest.Journal journal;
    private Lists lists;

    @BeforeEach
    void setUp() {
        repo = new Repo(); runs = new Runs(); publisher = new Publisher(); lists = new Lists();
        journal = new PersonVerificationTest.Journal();
    }

    private RescreenService service() {
        return new RescreenService(repo, runs, new ScreeningService(lists, 0.85), publisher, journal);
    }

    /** Alguien ya dado de alta y despejado contra las listas de ayer. */
    private PersonVerification alreadyCleared(String name) {
        var subject = new ScreeningSubject(UUID.randomUUID(), name,
                LocalDate.of(1985, 3, 12), null, null, "MX");
        var v = PersonVerification.start(UUID.randomUUID(), subject.partyId(), subject);
        v.recordScreening(new ScreeningOutcome(ScreeningStatus.CLEARED, List.of(), Set.of(VERSION)));
        v.recordIdentity(IdentityStatus.VERIFIED);
        return repo.save(v);
    }

    // ── Lo que el re-cruce detecta ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("alguien limpio ayer que hoy aparece publicado pasa a POTENTIAL_MATCH")
    void someonePublishedSinceIsCaught() {
        // Es la razón de existir de todo esto. Sin re-cruce, esta persona seguiría CLEARED para
        // siempre y podría disponer con un veredicto de hace años.
        var v = alreadyCleared("JUAN PEREZ GARCIA");
        lists.publish("JUAN PÉREZ GARCÍA");

        var run = service().rescreenAll(VERSION, RescreenService.TRIGGER_LIST_ACTIVATED);

        assertThat(v.getScreeningStatus()).isEqualTo(ScreeningStatus.POTENTIAL_MATCH);
        assertThat(run.getChangedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("a quien no le cambió nada, no se le republica")
    void unchangedVerdictsAreNotRepublished() {
        // Republicar a los N inunda el tópico y hace que party y credit-portfolio reescriban miles
        // de filas idénticas. Y pierde la señal: un evento debería significar «algo cambió».
        alreadyCleared("JUAN PEREZ GARCIA");
        alreadyCleared("ANA LOPEZ MARTINEZ");
        lists.publish("JUAN PÉREZ GARCÍA");

        var run = service().rescreenAll(VERSION, RescreenService.TRIGGER_LIST_ACTIVATED);

        assertThat(run.getTotalScreened()).isEqualTo(2);
        assertThat(run.getChangedCount()).isEqualTo(1);
        assertThat(publisher.publicados).hasSize(1);
    }

    // ── La decisión: no se invalida nada mientras corre ──────────────────────────────────────

    @Test
    @DisplayName("el re-cruce NO invalida veredictos por adelantado — se asume la ventana")
    void verdictsStayValidWhileRescreening() {
        // Decisión del negocio: marcar todo como caduco al activar una lista daría ventana cero y
        // detendría TODOS los desembolsos hasta terminar. Se eligió lo contrario. Esta prueba la
        // fija para que nadie la «arregle» creyendo que fue un olvido.
        var v = alreadyCleared("ANA LOPEZ MARTINEZ");   // a nadie se publica

        service().rescreenAll(VERSION, RescreenService.TRIGGER_LIST_ACTIVATED);

        assertThat(v.getScreeningStatus())
                .as("sigue valiendo: nada se detuvo")
                .isEqualTo(ScreeningStatus.CLEARED);
    }

    @Test
    @DisplayName("la corrida queda registrada con duración: es lo que hace defendible el riesgo")
    void theRunIsRecorded() {
        // Un riesgo asumido sin constancia es indistinguible de un descuido. La pregunta de una
        // revisión no será «¿corren re-cruces?» sino «¿cuándo corrió el que debía detectar esto?».
        alreadyCleared("JUAN PEREZ GARCIA");

        var run = service().rescreenAll(VERSION, RescreenService.TRIGGER_SCHEDULED);

        assertThat(run.getStatus()).isEqualTo(RescreenRunStatus.COMPLETED);
        assertThat(run.getTrigger()).isEqualTo("SCHEDULED");
        assertThat(run.getTriggeredByVersionId()).isEqualTo(VERSION);
        assertThat(run.duration()).isNotNull();
        assertThat(runs.rows).hasSize(1);
    }

    // ── Lo que NO se pisa ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("una adjudicación humana se conserva: el analista ve que ya lo había revisado")
    void ahumanAdjudicationIsNotErased() {
        // Si cumplimiento despejó a un homónimo y el re-cruce vuelve a encontrarlo, la persona
        // vuelve a la cola —eso es correcto, la lista cambió— pero conservando quién la despejó y
        // con qué argumento, para no partir de cero.
        var v = alreadyCleared("JUAN PEREZ GARCIA");
        v.adjudicate(ScreeningStatus.CLEARED, "analista-1", "nació en 1985, el de la lista en 1962");
        lists.publish("JUAN PÉREZ GARCÍA");

        service().rescreenAll(VERSION, RescreenService.TRIGGER_LIST_ACTIVATED);

        assertThat(v.getScreeningStatus()).isEqualTo(ScreeningStatus.POTENTIAL_MATCH);
        assertThat(v.getDecidedBy()).isEqualTo("analista-1");
        assertThat(v.getDecisionNotes()).contains("1962");
    }

    @Test
    @DisplayName("a quien no se puede cruzar NO se le declara limpio: se conserva su veredicto")
    void anUnscreenablePersonIsNotDeclaredClean() {
        // Es el fallo silencioso que todo el diseño evita: sin nombre guardado no hay contra qué
        // buscar, y poner CLEARED sería afirmar que se miró a quien no se pudo mirar.
        var sinDatos = PersonVerification.start(UUID.randomUUID(), UUID.randomUUID());
        repo.save(sinDatos);

        var run = service().rescreenAll(null, RescreenService.TRIGGER_MANUAL);

        assertThat(sinDatos.getScreeningStatus()).isEqualTo(ScreeningStatus.PENDING);
        assertThat(run.getChangedCount()).isZero();
        assertThat(run.getStatus()).isEqualTo(RescreenRunStatus.COMPLETED);
    }

    // ── Cuando algo se rompe a media corrida ─────────────────────────────────────────────────

    @Test
    @DisplayName("si falla a media corrida, se marca FAILED con motivo y se conserva lo avanzado")
    void afailedRunKeepsWhatItManaged() {
        alreadyCleared("JUAN PEREZ GARCIA");

        var blowsUp = new RescreenService(repo, runs, new ScreeningService(new Lists() {
            @Override public Set<UUID> activeVersionIds() { throw new IllegalStateException("base caída"); }
        }, 0.85), publisher, journal);

        assertThatThrownBy(() -> blowsUp.rescreenAll(VERSION, RescreenService.TRIGGER_SCHEDULED))
                .isInstanceOf(IllegalStateException.class);

        assertThat(runs.rows.values()).singleElement().satisfies(r -> {
            assertThat(r.getStatus()).isEqualTo(RescreenRunStatus.FAILED);
            assertThat(r.getFailureReason()).contains("base caída");
            assertThat(r.getFinishedAt()).isNotNull();
        });
    }

    @Test
    @DisplayName("recorre TODAS las páginas: nadie se queda sin mirar")
    void everyPageIsScreened() {
        // Con paginación es fácil dejar gente fuera sin que nada falle — y quien se queda fuera sale
        // «limpio» por omisión, que es el peor resultado posible de un control.
        for (int i = 0; i < 250; i++) alreadyCleared("PERSONA NUMERO " + i);

        var run = service().rescreenAll(null, RescreenService.TRIGGER_SCHEDULED);

        assertThat(run.getTotalScreened()).isEqualTo(250);
    }
}

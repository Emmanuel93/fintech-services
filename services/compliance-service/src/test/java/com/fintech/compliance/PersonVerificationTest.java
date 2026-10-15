package com.fintech.compliance;

import com.fintech.compliance.application.port.out.PersonVerificationRepository;
import com.fintech.compliance.application.port.out.VerificationEventPublisher;
import com.fintech.compliance.application.port.out.WatchlistSearchPort;
import com.fintech.compliance.application.service.PersonVerificationService;
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
 * El veredicto por persona: una vez, al alta, sin importar el rol.
 *
 * <p>Lo que estas pruebas fijan es la regla del negocio y sus dos filos:
 *
 * <ul>
 *   <li><b>Nada avanza sin veredicto</b> — y no haber comprobado no es haber salido limpio.</li>
 *   <li><b>Pero tiene que haber salida</b> — sin adjudicación, cualquier homónimo queda bloqueado
 *       para siempre y el control pasa de ser una compuerta a ser un muro.</li>
 * </ul>
 */
class PersonVerificationTest {

    private static final UUID VERSION   = UUID.randomUUID();
    private static final UUID PROSPECT_ID = UUID.randomUUID();
    private static final UUID PARTY     = UUID.randomUUID();

    // ── Dobles ───────────────────────────────────────────────────────────────────────────────

    private static final class Repo implements PersonVerificationRepository {
        final Map<UUID, PersonVerification> rows = new LinkedHashMap<>();
        @Override public Optional<PersonVerification> findByProspectId(UUID p) {
            return rows.values().stream().filter(v -> v.getProspectId().equals(p)).findFirst();
        }
        @Override public Optional<PersonVerification> findByPartyId(UUID p) {
            return rows.values().stream().filter(v -> p.equals(v.getPartyId())).findFirst();
        }
        @Override public Optional<PersonVerification> findById(UUID id) { return Optional.ofNullable(rows.get(id)); }
        @Override public PersonVerification save(PersonVerification v) { rows.put(v.getVerificationId(), v); return v; }
        @Override public java.util.List<PersonVerification> findPage(int page, int size) {
            var todos = java.util.List.copyOf(rows.values());
            int from = page * size;
            return from >= todos.size() ? java.util.List.of()
                    : todos.subList(from, Math.min(todos.size(), from + size));
        }
    }

    private static final class Publisher implements VerificationEventPublisher {
        int completados = 0; int confirmados = 0;
        @Override public void publishVerificationCompleted(PersonVerification v) { completados++; }
        @Override public void publishConfirmedMatch(PersonVerification v, String n, String p) { confirmados++; }
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

        Lists sanctioning(String name) {
            rows.add(WatchlistEntry.of(VERSION, WatchlistKind.SANCTIONS, name,
                    NormalizedName.of(name), null, null, null, null, "SDN", 2));
            return this;
        }
    }

    private Repo repo;
    private Publisher publisher;
    private Journal journal;

    @BeforeEach
    void setUp() { repo = new Repo(); publisher = new Publisher(); journal = new Journal(); }

    private PersonVerificationService serviceWith(Lists lists) {
        return new PersonVerificationService(repo, new ScreeningService(lists, 0.85), publisher, journal);
    }

    private ScreeningSubject subject(String name) {
        return new ScreeningSubject(PARTY, name, LocalDate.of(1985, 3, 12), null, null, "MX");
    }

    // ── Nada avanza sin veredicto ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("una persona limpia NO pasa todavía: su identidad sigue en revisión")
    void aCleanPersonStillWaitsForIdentity() {
        // Es la consecuencia directa de «el KYC bloquea»: sin proveedor, toda identidad va a la cola
        // de un analista, y hasta que alguien firme, la persona no avanza. No es un fallo: es la
        // regla, y conviene verla escrita para que nadie la descubra en producción.
        var v = serviceWith(new Lists()).evaluateNewPerson(subject("JUAN PEREZ GARCIA"), PROSPECT_ID);

        assertThat(v.getScreeningStatus()).isEqualTo(ScreeningStatus.CLEARED);
        assertThat(v.getIdentityStatus()).isEqualTo(IdentityStatus.IN_REVIEW);
        assertThat(v.allowsToProceed()).isFalse();
        assertThat(v.blockReason()).isEqualTo("identidad IN_REVIEW");
    }

    @Test
    @DisplayName("con las dos cosas resueltas, pasa")
    void withBothResolvedItProceeds() {
        var service = serviceWith(new Lists());
        var v = service.evaluateNewPerson(subject("JUAN PEREZ GARCIA"), PROSPECT_ID);

        service.adjudicateIdentity(v.getVerificationId(), IdentityStatus.VERIFIED,
                "analista@kredius.mx", "INE cotejada y prueba de vida superada");

        assertThat(repo.findById(v.getVerificationId())).get()
                .satisfies(x -> assertThat(x.allowsToProceed()).isTrue());
    }

    @Test
    @DisplayName("una coincidencia bloquea aunque la identidad esté verificada")
    void aMatchBlocksEvenWithVerifiedIdentity() {
        var service = serviceWith(new Lists().sanctioning("JUAN PÉREZ GARCÍA"));
        var v = service.evaluateNewPerson(subject("JUAN PEREZ GARCIA"), PROSPECT_ID);

        service.adjudicateIdentity(v.getVerificationId(), IdentityStatus.VERIFIED,
                "analista@kredius.mx", "INE cotejada y prueba de vida superada");

        var actualizada = repo.findById(v.getVerificationId()).orElseThrow();
        assertThat(actualizada.allowsToProceed()).isFalse();
        assertThat(actualizada.blockReason()).isEqualTo("listas POTENTIAL_MATCH");
    }

    @Test
    @DisplayName("si fallan las dos, el motivo las dice las dos")
    void bothReasonsAreReported() {
        var v = serviceWith(new Lists().sanctioning("JUAN PÉREZ GARCÍA"))
                .evaluateNewPerson(subject("JUAN PEREZ GARCIA"), PROSPECT_ID);

        assertThat(v.blockReason()).isEqualTo("identidad IN_REVIEW y listas POTENTIAL_MATCH");
    }

    // ── Idempotencia del consumo ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("el mismo prospecto dos veces NO se reevalúa: pisaría una adjudicación ya hecha")
    void reprocessingDoesNotOverwriteAnAdjudication() {
        // Kafka reentrega. Una segunda evaluación devolvería a la cola a alguien que cumplimiento ya
        // había despejado, y nadie lo notaría salvo la persona, que quedaría bloqueada otra vez.
        var service = serviceWith(new Lists().sanctioning("JUAN PÉREZ GARCÍA"));
        var v = service.evaluateNewPerson(subject("JUAN PEREZ GARCIA"), PROSPECT_ID);

        service.adjudicateScreening(v.getVerificationId(), ScreeningStatus.CLEARED,
                "analista-1", "nació en 1985, el de la lista en 1962");

        service.evaluateNewPerson(subject("JUAN PEREZ GARCIA"), PROSPECT_ID);   // reentrega

        assertThat(repo.rows).hasSize(1);
        assertThat(repo.findById(v.getVerificationId())).get()
                .satisfies(x -> assertThat(x.getScreeningStatus()).isEqualTo(ScreeningStatus.CLEARED));
    }

    // ── La salida: adjudicar ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("cumplimiento despeja un homónimo y la persona avanza")
    void complianceCanClearANamesake() {
        // Sin esto, el caso COMÚN —la homonimia— bloquea para siempre y el control es un muro.
        var service = serviceWith(new Lists().sanctioning("JUAN PÉREZ GARCÍA"));
        var v = service.evaluateNewPerson(subject("JUAN PEREZ GARCIA"), PROSPECT_ID);
        service.adjudicateIdentity(v.getVerificationId(), IdentityStatus.VERIFIED,
                "analista@kredius.mx", "INE cotejada y prueba de vida superada");

        var cleared = service.adjudicateScreening(v.getVerificationId(), ScreeningStatus.CLEARED,
                "analista-1", "fecha de nacimiento distinta y el de la lista es sirio");

        assertThat(cleared.allowsToProceed()).isTrue();
        assertThat(cleared.getDecidedBy()).isEqualTo("analista-1");
        assertThat(cleared.getDecisionNotes()).contains("sirio");
    }

    @Test
    @DisplayName("un veredicto de listas sin autor o sin argumento no puede existir")
    void anAdjudicationNeedsAnAuthorAndAnArgument() {
        var service = serviceWith(new Lists().sanctioning("JUAN PÉREZ GARCÍA"));
        var v = service.evaluateNewPerson(subject("JUAN PEREZ GARCIA"), PROSPECT_ID);

        assertThatThrownBy(() -> service.adjudicateScreening(
                v.getVerificationId(), ScreeningStatus.CLEARED, "  ", "porque sí"))
                .hasMessageContaining("sin autor");

        assertThatThrownBy(() -> service.adjudicateScreening(
                v.getVerificationId(), ScreeningStatus.CLEARED, "analista-1", "  "))
                .hasMessageContaining("Falta el argumento");
    }

    @Test
    @DisplayName("adjudicar es despejar o confirmar; no se puede «adjudicar» a pendiente")
    void adjudicatingToPendingIsNotADecision() {
        var service = serviceWith(new Lists().sanctioning("JUAN PÉREZ GARCÍA"));
        var v = service.evaluateNewPerson(subject("JUAN PEREZ GARCIA"), PROSPECT_ID);

        assertThatThrownBy(() -> service.adjudicateScreening(
                v.getVerificationId(), ScreeningStatus.PENDING, "analista-1", "no sé"))
                .hasMessageContaining("no es una decisión");
    }

    // ── El match confirmado obliga a algo más que rechazar ───────────────────────────────────

    @Test
    @DisplayName("confirmar una coincidencia publica el hecho regulatorio, aparte del veredicto")
    void aConfirmedMatchPublishesTheRegulatoryFact() {
        // No termina en «rechazar al cliente»: genera obligación de reporte. Va en su propio tópico
        // para que `audit` escriba el UIFReport sin tener que filtrar todos los veredictos.
        var service = serviceWith(new Lists().sanctioning("JUAN PÉREZ GARCÍA"));
        var v = service.evaluateNewPerson(subject("JUAN PEREZ GARCIA"), PROSPECT_ID);

        service.adjudicateScreening(v.getVerificationId(), ScreeningStatus.CONFIRMED_MATCH,
                "analista-1", "misma persona: coincide fecha y documento");

        assertThat(publisher.confirmados).isEqualTo(1);
        assertThat(repo.findById(v.getVerificationId())).get()
                .satisfies(x -> assertThat(x.allowsToProceed()).isFalse());
    }

    @Test
    @DisplayName("despejar NO publica hecho regulatorio")
    void clearingDoesNotPublishARegulatoryFact() {
        var service = serviceWith(new Lists().sanctioning("JUAN PÉREZ GARCÍA"));
        var v = service.evaluateNewPerson(subject("JUAN PEREZ GARCIA"), PROSPECT_ID);

        service.adjudicateScreening(v.getVerificationId(), ScreeningStatus.CLEARED,
                "analista-1", "homónimo: otra fecha de nacimiento");

        assertThat(publisher.confirmados).isZero();
    }

    @Test
    @DisplayName("cada cambio de veredicto se publica, para que party lo guarde")
    void everyChangeIsPublished() {
        var service = serviceWith(new Lists());
        var v = service.evaluateNewPerson(subject("JUAN PEREZ GARCIA"), PROSPECT_ID);
        service.adjudicateIdentity(v.getVerificationId(), IdentityStatus.VERIFIED,
                "analista@kredius.mx", "INE cotejada y prueba de vida superada");

        assertThat(publisher.completados).isEqualTo(2);
    }
    // ── La cola de identidad, que ahora tiene salida ─────────────────────────────────────────

    @Test
    @DisplayName("el dictamen de identidad exige AUTOR: una decisión regulatoria sin firma no existe")
    void anidentityVerdictNeedsAnAuthor() {
        // La asimetría anterior no tenía fundamento: el veredicto de listas exigía autor y nota, y
        // el de identidad se podía escribir sin nada. Las dos son decisiones sobre una persona ante
        // un regulador, y las dos tienen que poder sustentarse.
        var service = serviceWith(new Lists());
        var v = service.evaluateNewPerson(subject("JUAN PÉREZ GARCÍA"), PROSPECT_ID);

        assertThatThrownBy(() -> service.adjudicateIdentity(
                v.getVerificationId(), IdentityStatus.VERIFIED, "  ", "cotejada"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("autor");
    }

    @Test
    @DisplayName("y exige ARGUMENTO: es lo que sustenta el dictamen ante una revisión")
    void anidentityVerdictNeedsItsReasoning() {
        var service = serviceWith(new Lists());
        var v = service.evaluateNewPerson(subject("JUAN PÉREZ GARCÍA"), PROSPECT_ID);

        assertThatThrownBy(() -> service.adjudicateIdentity(
                v.getVerificationId(), IdentityStatus.VERIFIED, "analista@kredius.mx", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("argumento");
    }

    @Test
    @DisplayName("dictaminar no es dejar en revisión: IN_REVIEW y PENDING no son decisiones")
    void leavingItInTheQueueIsNotAdecision() {
        // Permitirlo dejaría que un analista «firmara» sin decidir, y la persona seguiría bloqueada
        // con un dictamen que parece resuelto. Es peor que no firmar.
        var service = serviceWith(new Lists());
        var v = service.evaluateNewPerson(subject("JUAN PÉREZ GARCÍA"), PROSPECT_ID);

        for (IdentityStatus noEsDecision : new IdentityStatus[]{IdentityStatus.PENDING, IdentityStatus.IN_REVIEW}) {
            assertThatThrownBy(() -> service.adjudicateIdentity(
                    v.getVerificationId(), noEsDecision, "analista@kredius.mx", "x"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("no es una decisión");
        }
    }

    @Test
    @DisplayName("el dictamen REPUBLICA el veredicto: sin eso quedaría firmado y sin efecto")
    void adjudicatingIdentityRepublishesTheVerdict() {
        // Es el detalle que lo hace servir. El dictamen vive aquí, pero quien bloquea es `party` y
        // las proyecciones de las compuertas: sin republicar, la persona seguiría bloqueada río
        // abajo con su identidad ya comprobada aquí — firmado y sin efecto, el peor de los mundos.
        var service = serviceWith(new Lists());
        var v = service.evaluateNewPerson(subject("JUAN PÉREZ GARCÍA"), PROSPECT_ID);
        int publicadosAlEvaluar = publisher.completados;

        service.adjudicateIdentity(v.getVerificationId(), IdentityStatus.VERIFIED,
                "analista@kredius.mx", "INE cotejada y prueba de vida superada");

        assertThat(publisher.completados).isEqualTo(publicadosAlEvaluar + 1);
    }

    @Test
    @DisplayName("un rechazo de identidad también se registra, con su autor y su motivo")
    void arejectionIsRecordedToo() {
        var service = serviceWith(new Lists());
        var v = service.evaluateNewPerson(subject("JUAN PÉREZ GARCÍA"), PROSPECT_ID);

        var dictaminada = service.adjudicateIdentity(v.getVerificationId(), IdentityStatus.REJECTED,
                "analista@kredius.mx", "La selfie no corresponde a la INE presentada");

        assertThat(dictaminada.getIdentityStatus()).isEqualTo(IdentityStatus.REJECTED);
        assertThat(dictaminada.allowsToProceed()).isFalse();
        assertThat(dictaminada.getDecidedBy()).isEqualTo("analista@kredius.mx");
    }

    @Test
    @DisplayName("sólo con las DOS resueltas la persona puede operar")
    void bothVerdictsAreNeeded() {
        // Es la compuerta completa: identidad comprobada Y listas despejadas. Cada mitad se resuelve
        // por un camino distinto, y tener una no implica tener la otra.
        var service = serviceWith(new Lists().sanctioning("JUAN PEREZ GARCIA"));
        var v = service.evaluateNewPerson(subject("JUAN PÉREZ GARCÍA"), PROSPECT_ID);

        var conIdentidad = service.adjudicateIdentity(v.getVerificationId(), IdentityStatus.VERIFIED,
                "analista@kredius.mx", "INE cotejada");
        assertThat(conIdentidad.allowsToProceed())
                .as("identidad sí, listas no: sigue bloqueada")
                .isFalse();

        var cleared = service.adjudicateScreening(v.getVerificationId(), ScreeningStatus.CLEARED,
                "cumplimiento@kredius.mx", "Nació en 1985; el de la lista, en 1962");
        assertThat(cleared.allowsToProceed()).isTrue();
    }

    // ── La bitácora: que se consultó, cuándo y contra qué ────────────────────────────────────

    @Test
    @DisplayName("el alta deja constancia de la consulta, no sólo del veredicto")
    void theonboardingCheckIsJournalled() {
        // El veredicto contesta «¿puede operar hoy?». La constancia contesta la pregunta de una
        // revisión: «¿la consultaron, cuándo, y qué decía la lista ese día?».
        var service = serviceWith(new Lists());
        service.evaluateNewPerson(subject("JUAN PÉREZ GARCÍA"), PROSPECT_ID);

        assertThat(journal.rows).singleElement().satisfies(c -> {
            assertThat(c.getProspectId()).isEqualTo(PROSPECT_ID);
            assertThat(c.getTrigger()).isEqualTo(ScreeningTrigger.ONBOARDING);
            assertThat(c.getCheckedAt()).isNotNull();
        });
    }

    @Test
    @DisplayName("la constancia guarda los DATOS con los que se consultó, no sólo el resultado")
    void thejournalRecordsWhatWasQueriedWith() {
        // Si la persona corrige su nombre después, el resultado de entonces sólo se explica con el
        // nombre de entonces. Sin esto, una consulta vieja parecería mal hecha.
        var service = serviceWith(new Lists());
        service.evaluateNewPerson(new ScreeningSubject(
                PARTY, "JUAN PÉREZ GARCÍA", LocalDate.of(1985, 3, 12),
                "PEGJ850312HDFXXX01", "PEGJ850312XXX", "MX"), PROSPECT_ID);

        assertThat(journal.rows).singleElement().satisfies(c -> {
            assertThat(c.getSubjectFullName()).isEqualTo("JUAN PÉREZ GARCÍA");
            assertThat(c.getSubjectCurp()).isEqualTo("PEGJ850312HDFXXX01");
            assertThat(c.getSubjectDateOfBirth()).isEqualTo(LocalDate.of(1985, 3, 12));
        });
    }

    @Test
    @DisplayName("guarda CONTRA QUÉ VERSIONES se consultó: sin eso el resultado no es reproducible")
    void thejournalRecordsTheListVersions() {
        // «Salió limpia» sin decir contra qué lista no sustenta nada: la lista de ese día ya no es
        // la de hoy. Con la versión, cualquiera puede volver a correr la consulta y obtener lo mismo.
        var service = serviceWith(new Lists().sanctioning("IVAN PETROV"));
        service.evaluateNewPerson(subject("JUAN PÉREZ GARCÍA"), PROSPECT_ID);

        assertThat(journal.rows).singleElement().satisfies(c -> {
            assertThat(c.hadListsToCheckAgainst()).isTrue();
            assertThat(c.getListVersions()).contains(VERSION.toString());
        });
    }

    @Test
    @DisplayName("una consulta con coincidencia guarda a QUIÉN se parecía y por qué")
    void amatchIsJournalledWithItsEvidence() {
        // Distingue «no coincidió con nadie» de «coincidió con alguien» — que es trabajo hecho y
        // hay que poder mostrarlo años después sin reconstruirlo.
        var service = serviceWith(new Lists().sanctioning("JUAN PEREZ GARCIA"));
        service.evaluateNewPerson(subject("JUAN PÉREZ GARCÍA"), PROSPECT_ID);

        assertThat(journal.rows).singleElement().satisfies(c -> {
            assertThat(c.getResult()).isEqualTo(ScreeningStatus.POTENTIAL_MATCH);
            assertThat(c.getCandidateCount()).isPositive();
            assertThat(c.getStrongestListedName()).isEqualTo("JUAN PEREZ GARCIA");
            assertThat(c.getStrongestReason()).isNotNull();
        });
    }

    @Test
    @DisplayName("una consulta SIN listas vigentes queda marcada como que no comprobó nada")
    void acheckWithoutListsIsMarkedAsSuch() {
        // Es la distinción que evita el peor malentendido del expediente: «PENDING con cero
        // candidatos» se parece demasiado a «limpio», y significa lo contrario.
        var withoutLists = new Lists() {
            @Override public java.util.Set<UUID> activeVersionIds() { return java.util.Set.of(); }
        };
        serviceWith(withoutLists).evaluateNewPerson(subject("JUAN PÉREZ GARCÍA"), PROSPECT_ID);

        assertThat(journal.rows).singleElement().satisfies(c -> {
            assertThat(c.getResult()).isEqualTo(ScreeningStatus.PENDING);
            assertThat(c.hadListsToCheckAgainst())
                    .as("no había contra qué comprobar, y el expediente tiene que decirlo")
                    .isFalse();
        });
    }

    @Test
    @DisplayName("la evaluación bajo demanda se distingue del alta: demuestran cosas distintas")
    void anondemandCheckIsDistinguishable() {
        // Una del alta es diligencia al dar entrada a alguien; una bajo demanda es que alguien
        // preguntó. Para una revisión no son lo mismo.
        var service = serviceWith(new Lists());
        service.evaluateOnDemand(subject("JUAN PÉREZ GARCÍA"), PROSPECT_ID);

        assertThat(journal.rows).singleElement().satisfies(c ->
                assertThat(c.getTrigger()).isEqualTo(ScreeningTrigger.ON_DEMAND));
    }

    @Test
    @DisplayName("una reentrega de Kafka NO duplica la constancia: no se volvió a consultar")
    void aredeliveryDoesNotDuplicateTheRecord() {
        // La evaluación es idempotente por prospecto, así que la segunda vez no hay consulta nueva
        // que registrar. Registrarla igual inventaría diligencia que no ocurrió.
        var service = serviceWith(new Lists());
        service.evaluateNewPerson(subject("JUAN PÉREZ GARCÍA"), PROSPECT_ID);
        service.evaluateNewPerson(subject("JUAN PÉREZ GARCÍA"), PROSPECT_ID);

        assertThat(journal.rows).hasSize(1);
    }


    /**
     * Bitácora en memoria. Está aquí y no sustituida por un mock porque las pruebas <b>afirman</b>
     * sobre ella: que cada consulta deja una fila es parte de lo que hay que probar, no plomería.
     */
    static final class Journal extends com.fintech.compliance.application.service.ScreeningJournal {
        final java.util.List<com.fintech.compliance.domain.ScreeningCheck> rows = new java.util.ArrayList<>();

        Journal() {
            super(new com.fintech.compliance.application.port.out.ScreeningCheckRepository() {
                @Override public com.fintech.compliance.domain.ScreeningCheck save(
                        com.fintech.compliance.domain.ScreeningCheck c) { return c; }
                @Override public java.util.List<com.fintech.compliance.domain.ScreeningCheck>
                        findByProspectId(java.util.UUID p, int limit) { return java.util.List.of(); }
                @Override public long countByProspectId(java.util.UUID p) { return 0; }
            });
        }

        @Override
        public void record(java.util.UUID prospectId, com.fintech.compliance.domain.ScreeningSubject subject,
                           com.fintech.compliance.domain.ScreeningTrigger trigger,
                           com.fintech.compliance.domain.ScreeningOutcome outcome) {
            rows.add(com.fintech.compliance.domain.ScreeningCheck.of(prospectId, subject, trigger, outcome));
        }
    }

}

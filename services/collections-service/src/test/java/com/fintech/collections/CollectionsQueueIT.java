package com.fintech.collections;

import com.fintech.collections.application.ContactQueueRow;
import com.fintech.collections.application.PromiseQueueRow;
import com.fintech.collections.application.port.out.CollectionsQueueRepository;
import com.fintech.collections.domain.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Las bandejas transversales contra Postgres real.
 *
 * <p>Se prueban aquí y no con mocks porque lo que hay que verificar es precisamente lo que un mock
 * no puede: que el {@code JOIN} y las dos subconsultas correlacionadas sean SQL válido, que el
 * filtro por colección vacía no genere un {@code IN ()}, y que la paginación cuente bien sobre un
 * {@code JOIN} — tres cosas que compilan perfectamente y fallan en ejecución.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class CollectionsQueueIT {

    @Autowired CollectionsQueueRepository queue;
    @Autowired EntityManager em;

    /**
     * Gestores únicos por corrida.
     *
     * <p>La suite comparte contenedor y otras pruebas siembran casos y promesas, así que afirmar
     * sobre totales globales haría que estos tests pasaran solos y fallaran acompañados — que es
     * la peor clase de test. Todo se filtra por estos dos gestores, que no existen fuera de aquí.
     */
    /** La misma zona que ancla el día en la consulta. Si una cambia, la otra tiene que cambiar. */
    private static final java.time.ZoneId ZONA_OPERATIVA = java.time.ZoneId.of("America/Mexico_City");

    private String agentA;
    private String agentB;
    private List<String> agents;

    private UUID caseA;
    private UUID caseB;
    /** Los ids que siembra esta prueba. Se afirma sobre ellos y no sobre conteos globales. */
    private final List<UUID> seededPromises = new ArrayList<>();

    @BeforeEach
    void seed() {
        seededPromises.clear();
        agentA = "agent-A-" + UUID.randomUUID();
        agentB = "agent-B-" + UUID.randomUUID();
        agents = List.of(agentA, agentB);

        caseA = openCase(agentA, 45, DelinquencyBucket.B31_60, "PERSONAL_LOAN");
        caseB = openCase(agentB, 5,  DelinquencyBucket.B1_30,  "REVOLVING_LINE");

        // PP-01: sólo una promesa ACTIVE por caso (índice único parcial). La rota se siembra
        // primero y se cierra antes de abrir la vigente — que es exactamente el orden en que
        // ocurre en la vida real: se rompe una y después se pacta otra.
        promise(caseA, "900.00",  LocalDate.now().minusDays(3), PromiseStatus.BROKEN);
        promise(caseA, "1500.00", LocalDate.now(), PromiseStatus.ACTIVE);
        promise(caseB, "2200.00", LocalDate.now().plusDays(4), PromiseStatus.ACTIVE);

        // Dos intentos hoy sobre el caso A; uno viejo sobre el B.
        // Anclados al MISMO comienzo de día que usa la consulta, no a `now()` de la máquina.
        //
        // `attemptsToday` cuenta desde el inicio del día en **hora de México**, y con razón: el tope
        // de intentos diarios es una regla de trato al cliente, y su día es el de la persona a la
        // que se le llama. Sembrar con «hace tres horas» sobre el reloj de la máquina daba un
        // fixture que sólo era correcto según la hora a la que corriera la prueba: en la ventana
        // entre la medianoche mexicana y la de la máquina, los dos intentos caían en el día
        // anterior y la cuenta salía en cero.
        //
        // No era sensibilidad a la carga —así estaba escrito en la deuda del README— sino a la
        // hora del día, y se reproduce puntualmente una hora cada día.
        Instant iniciaElDiaEnMexico = LocalDate.now(ZONA_OPERATIVA)
                .atStartOfDay(ZONA_OPERATIVA).toInstant();
        attempt(caseA, ContactResult.NO_ANSWER, iniciaElDiaEnMexico);
        attempt(caseA, ContactResult.ANSWERED,  iniciaElDiaEnMexico.plusSeconds(1));
        attempt(caseB, ContactResult.DELIVERED, iniciaElDiaEnMexico.minus(10, ChronoUnit.DAYS));
        em.flush();
    }

    // ── Promesas ─────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("sin filtros trae todas las promesas, con el contexto de su caso")
    void unfilteredBringsEveryPromiseWithCaseContext() {
        Page<PromiseQueueRow> page = queue.searchPromises(null, null, agents, null, null, PageRequest.of(0, 20));

        assertThat(page.getContent()).extracting(PromiseQueueRow::promiseId)
                .containsExactlyInAnyOrderElementsOf(seededPromises);
        PromiseQueueRow row = page.getContent().stream()
                .filter(r -> r.caseId().equals(caseB)).findFirst().orElseThrow();
        assertThat(row.assignedAgentId()).isEqualTo(agentB);
        assertThat(row.currentBucket()).isEqualTo(DelinquencyBucket.B1_30);
        assertThat(row.productType()).isEqualTo("REVOLVING_LINE");
        assertThat(row.daysDelinquent()).isEqualTo(5);
    }

    @Test
    @DisplayName("filtra por estado, tramo y gestor — cada uno por su lado")
    void filtersApplyIndependently() {
        assertThat(queue.searchPromises(List.of(PromiseStatus.ACTIVE), null, agents, null, null,
                PageRequest.of(0, 20)).getTotalElements()).isEqualTo(2);

        assertThat(queue.searchPromises(null, List.of(DelinquencyBucket.B31_60), agents, null, null,
                PageRequest.of(0, 20)).getTotalElements()).isEqualTo(2);

        assertThat(queue.searchPromises(null, null, List.of(agentB), null, null,
                PageRequest.of(0, 20)).getTotalElements()).isEqualTo(1);
    }

    @Test
    @DisplayName("una colección vacía significa «todos», no «ninguno»")
    void emptyCollectionMeansNoFilter() {
        // Es el caso que produce un IN () y revienta si el adaptador no normaliza a null: los
        // dos primeros filtros van vacíos a propósito. El de gestor sí lleva valores porque es lo
        // que acota la prueba a sus propios datos.
        assertThat(queue.searchPromises(List.of(), List.of(), agents, null, null,
                PageRequest.of(0, 20)).getContent())
                .extracting(PromiseQueueRow::promiseId)
                .containsExactlyInAnyOrderElementsOf(seededPromises);
    }

    @Test
    @DisplayName("el rango de fecha prometida acota, y sin rango no filtra")
    void promisedDateRangeNarrows() {
        LocalDate today = LocalDate.now();

        // Con rango: sólo la que vence hoy.
        assertThat(queue.searchPromises(null, null, agents, today, today, PageRequest.of(0, 20))
                .getContent())
                .extracting(PromiseQueueRow::promisedDate)
                .containsExactly(today);

        // Sin rango: las tres sembradas. Se afirma por id y no por conteo — la suite comparte
        // contenedor y otras pruebas dejan promesas suyas en la tabla.
        assertThat(queue.searchPromises(null, null, agents, null, null, PageRequest.of(0, 20))
                .getContent())
                .extracting(PromiseQueueRow::promiseId)
                .containsExactlyInAnyOrderElementsOf(seededPromises);
    }

    @Test
    @DisplayName("cada promesa trae los intentos de hoy de su caso y el resultado del último contacto")
    void promisesCarryTodaysAttemptsAndLastResult() {
        Page<PromiseQueueRow> page = queue.searchPromises(null, null, List.of(agentA), null, null,
                PageRequest.of(0, 20));

        PromiseQueueRow row = page.getContent().get(0);
        assertThat(row.attemptsToday()).isEqualTo(2);
        assertThat(row.lastContactResult()).isEqualTo(ContactResult.ANSWERED);

        // El caso B tuvo un intento, pero hace diez días: hoy lleva cero.
        PromiseQueueRow other = queue.searchPromises(null, null, List.of(agentB), null, null,
                PageRequest.of(0, 20)).getContent().get(0);
        assertThat(other.attemptsToday()).isZero();
        assertThat(other.lastContactResult()).isEqualTo(ContactResult.DELIVERED);
    }

    @Test
    @DisplayName("la paginación cuenta sobre el JOIN, no sobre la tabla")
    void paginationCountsOverTheJoin() {
        Page<PromiseQueueRow> first = queue.searchPromises(null, null, agents, null, null, PageRequest.of(0, 2));

        assertThat(first.getContent()).hasSize(2);
        assertThat(first.getTotalElements()).isEqualTo(seededPromises.size());
        assertThat(first.getTotalPages()).isEqualTo(2);
    }

    // ── Contactos ────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("los intentos cruzan casos y traen tramo y gestor")
    void contactAttemptsCrossCases() {
        Page<ContactQueueRow> page = queue.searchContactAttempts(null, null, null, agents, null, null,
                PageRequest.of(0, 20));

        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getContent()).allSatisfy(r -> assertThat(r.assignedAgentId()).isNotBlank());
    }

    @Test
    @DisplayName("filtra por resultado y por rango de fecha del intento")
    void contactFiltersApply() {
        assertThat(queue.searchContactAttempts(List.of(ContactResult.ANSWERED), null, null, agents, null, null,
                PageRequest.of(0, 20)).getTotalElements()).isEqualTo(1);

        assertThat(queue.searchContactAttempts(null, null, null, agents,
                Instant.now().minus(2, ChronoUnit.DAYS), Instant.now(),
                PageRequest.of(0, 20)).getTotalElements()).isEqualTo(2);
    }

    // ── Semillas ─────────────────────────────────────────────────────────────────────────────

    private UUID openCase(String agentId, int days, DelinquencyBucket bucket, String productType) {
        CollectionCase c = CollectionCase.open(UUID.randomUUID(), UUID.randomUUID(), productType,
                days, new BigDecimal("10000"), "STANDARD");
        c.assignAgent(agentId);
        em.persist(c);
        // El bucket y los días se derivan al abrir; se fuerzan aquí para que la semilla sea
        // explícita y el test no dependa de la fecha en que corra.
        em.createQuery("UPDATE CollectionCase c SET c.currentBucket = :b, c.daysDelinquent = :d "
                     + "WHERE c.caseId = :id")
                .setParameter("b", bucket).setParameter("d", days)
                .setParameter("id", c.getCaseId()).executeUpdate();
        return c.getCaseId();
    }

    /**
     * Siembra una promesa en cualquier estado y fecha.
     *
     * <p>El dominio prohíbe crear una promesa con fecha pasada —una promesa de pago para ayer no
     * es una promesa—, así que una promesa rota se siembra como el sistema la produce: nace hoy y
     * después se le mueve la fecha y el estado. Saltarse la fábrica para forzar el dato inicial
     * sería probar contra un estado que el dominio nunca genera.
     */
    private UUID promise(UUID caseId, String amount, LocalDate date, PromiseStatus status) {
        PaymentPromise p = PaymentPromise.create(caseId, new BigDecimal(amount), LocalDate.now(), "agent-seed");
        em.persist(p);
        if (status != PromiseStatus.ACTIVE || !date.equals(LocalDate.now())) {
            em.createQuery("UPDATE PaymentPromise p SET p.status = :s, p.promisedDate = :d "
                         + "WHERE p.promiseId = :id")
                    .setParameter("s", status).setParameter("d", date)
                    .setParameter("id", p.getPromiseId()).executeUpdate();
        }
        // El índice único parcial se evalúa al escribir: sin vaciar aquí, la siguiente promesa
        // del mismo caso choca contra una fila que todavía figura como ACTIVE en la sesión.
        em.flush();
        seededPromises.add(p.getPromiseId());
        return p.getPromiseId();
    }

    private void attempt(UUID caseId, ContactResult result, Instant at) {
        ContactAttempt a = ContactAttempt.record(caseId, ContactChannel.PHONE, result, "agent-seed");
        em.persist(a);
        em.createQuery("UPDATE ContactAttempt a SET a.attemptedAt = :at WHERE a.attemptId = :id")
                .setParameter("at", at).setParameter("id", a.getAttemptId()).executeUpdate();
    }
}

package com.fintech.closing;

import com.fintech.closing.application.ClosingProperties;
import com.fintech.closing.application.port.in.CloseUnitProcessor;
import com.fintech.closing.application.port.out.CloseRunRepository;
import com.fintech.closing.application.port.out.CloseUnitRepository;
import com.fintech.closing.application.service.CloseRunPlanner;
import com.fintech.closing.application.service.CloseRunWorker;
import com.fintech.closing.application.service.LeaseReaper;
import com.fintech.closing.domain.ClosePhase;
import com.fintech.closing.domain.CloseRun;
import com.fintech.closing.domain.CloseUnit;
import com.fintech.closing.domain.UnitStatus;
import com.fintech.shared.lock.DistributedLock;
import com.fintech.shared.lock.LockKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TK-05 — el motor de corridas contra Postgres y Redis reales.
 *
 * <p>Lo que se prueba no es que el código corra: es que <b>tres pods se repartan el trabajo sin
 * solaparse, que un pod muerto no se lleve su lote en silencio, y que el titular de un candado
 * vencido no pueda escribir</b>. Ninguna de las tres cosas se ve con mocks.
 */
@SpringBootTest
@Testcontainers
class MotorDeCorridasIT {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("fintech").withUsername("fintech").withPassword("fintech");

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.data.redis.host", REDIS::getHost);
        r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired CloseRunPlanner planner;
    @Autowired CloseRunWorker worker;
    @Autowired LeaseReaper reaper;
    @Autowired CloseRunRepository runs;
    @Autowired CloseUnitRepository units;
    @Autowired DistributedLock lock;
    @Autowired ClosingProperties properties;
    @Autowired JdbcTemplate jdbc;

    LocalDate fecha;

    @BeforeEach
    void limpiar() {
        jdbc.update("DELETE FROM closing.close_units");
        jdbc.update("DELETE FROM closing.close_runs");
        fecha = LocalDate.of(2026, 8, 24);
    }

    /** Materializa N unidades ficticias. RECONCILE no exige fase previa, así que sirve de banco de pruebas. */
    private CloseRun planificar(int n) {
        return planner.plan(fecha, ClosePhase.RECONCILE, "ALL", run -> {
            List<CloseUnit> lista = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                lista.add(CloseUnit.of(run.getRunId(), "cuenta-" + i, UUID.randomUUID(), "PERSONAL_LOAN"));
            }
            return lista;
        }).orElseThrow().run();
    }

    private CloseUnitProcessor procesadorQue(java.util.function.Consumer<CloseUnit> accion) {
        return new CloseUnitProcessor() {
            @Override public ClosePhase phase() { return ClosePhase.RECONCILE; }
            @Override public void process(CloseUnit unit, LocalDate businessDate) { accion.accept(unit); }
        };
    }

    // ── Planificación ────────────────────────────────────────────────────────

    @Test
    @DisplayName("un solo pod planifica: los demás encuentran la corrida ya hecha")
    void unSoloPlanificador() throws Exception {
        int pods = 6;
        ExecutorService pool = Executors.newFixedThreadPool(pods);
        CountDownLatch salida = new CountDownLatch(1);
        CountDownLatch fin = new CountDownLatch(pods);
        AtomicInteger creadores = new AtomicInteger();

        for (int i = 0; i < pods; i++) {
            pool.submit(() -> {
                try {
                    salida.await();
                    planner.plan(fecha, ClosePhase.RECONCILE, "ALL", run -> {
                        List<CloseUnit> l = new ArrayList<>();
                        for (int k = 0; k < 10; k++) {
                            l.add(CloseUnit.of(run.getRunId(), "c-" + k, UUID.randomUUID(), "PERSONAL_LOAN"));
                        }
                        return l;
                    }).ifPresent(p -> { if (p.creadaPorEstePod()) creadores.incrementAndGet(); });
                } catch (Exception ignored) {
                } finally { fin.countDown(); }
            });
        }
        salida.countDown();
        assertThat(fin.await(60, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        assertThat(creadores.get()).as("exactamente un pod planificó").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM closing.close_units", Integer.class))
                .as("las unidades se materializaron una sola vez").isEqualTo(10);
    }

    @Test
    @DisplayName("una fase no arranca si la anterior no ha sellado")
    void barreraDeFase() {
        // Sustituye a los offsets de reloj del as-is: si el devengo se alarga, la mora espera
        // en vez de correr sobre datos viejos.
        assertThatThrownBy(() -> planner.plan(fecha, ClosePhase.ACCRUAL, "ALL", run -> List.of()))
                .isInstanceOf(CloseRunPlanner.FaseNoLista.class)
                .hasMessageContaining("RECONCILE todavía no ha sellado");
    }

    @Test
    @DisplayName("sellada la fase previa, la siguiente sí planifica")
    void conLaPreviaSelladaSiArranca() {
        CloseRun reconcile = planificar(0);
        reconcile.seal();
        runs.save(reconcile);

        assertThat(planner.plan(fecha, ClosePhase.ACCRUAL, "ALL", run -> List.of())).isPresent();
    }

    // ── Reparto ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("tres pods se reparten 60 unidades sin procesar ninguna dos veces")
    void tresPodsSeReparten() throws Exception {
        CloseRun run = planificar(60);
        Set<String> procesadas = ConcurrentHashMap.newKeySet();
        AtomicInteger dobles = new AtomicInteger();

        var procesador = procesadorQue(u -> {
            if (!procesadas.add(u.getUnitKey())) dobles.incrementAndGet();
        });

        int pods = 3;
        ExecutorService pool = Executors.newFixedThreadPool(pods);
        CountDownLatch fin = new CountDownLatch(pods);
        for (int p = 0; p < pods; p++) {
            final int offset = p;
            pool.submit(() -> {
                try {
                    // Cada pod arranca en un desplazamiento distinto: sin eso, las tres réplicas
                    // competirían siempre por las mismas filas y el candado rechazaría casi todo.
                    for (int i = 0; i < 30; i++) {
                        var r = worker.workBatch(run.getRunId(), fecha, procesador, offset);
                        if (!r.huboTrabajo() && r.colisiones() == 0) break;
                    }
                } finally { fin.countDown(); }
            });
        }
        assertThat(fin.await(120, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        assertThat(dobles.get()).as("ninguna unidad se procesó dos veces").isZero();
        assertThat(procesadas).as("todas se procesaron").hasSize(60);
        assertThat(units.countByStatus(run.getRunId(), UnitStatus.DONE.name())).isEqualTo(60);
    }

    @Test
    @DisplayName("una unidad que falla no aborta la corrida y queda con su causa")
    void elFalloNoAbortaLaCorrida() {
        CloseRun run = planificar(5);

        var procesador = procesadorQue(u -> {
            if (u.getUnitKey().equals("cuenta-2")) {
                throw new IllegalStateException("saldo inconsistente en la cuenta");
            }
        });

        worker.workBatch(run.getRunId(), fecha, procesador, 0);

        assertThat(units.countByStatus(run.getRunId(), UnitStatus.DONE.name())).isEqualTo(4);
        assertThat(units.countByStatus(run.getRunId(), UnitStatus.FAILED.name())).isEqualTo(1);

        String causa = jdbc.queryForObject(
                "SELECT last_error FROM closing.close_units WHERE unit_key = 'cuenta-2'", String.class);
        assertThat(causa).as("la causa se persiste; el as-is la pierde en un log")
                .contains("saldo inconsistente");
    }

    @Test
    @DisplayName("una unidad omitida no cuenta como fallo")
    void omitidaNoEsFallo() {
        CloseRun run = planificar(3);
        var procesador = procesadorQue(u -> {
            if (u.getUnitKey().equals("cuenta-1")) {
                throw new CloseRunWorker.UnitSkipped("la política del producto la excluye");
            }
        });

        worker.workBatch(run.getRunId(), fecha, procesador, 0);

        assertThat(units.countByStatus(run.getRunId(), UnitStatus.SKIPPED.name())).isEqualTo(1);
        assertThat(units.countByStatus(run.getRunId(), UnitStatus.FAILED.name())).isZero();
    }

    // ── Pod muerto ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("el pod muere con la unidad tomada: el reaper la devuelve y otro la termina")
    void elReaperRecuperaElLoteDelPodMuerto() {
        CloseRun run = planificar(1);
        CloseUnit unidad = units.findPending(run.getRunId(), 0, 1).get(0);

        // Se simula el pod que muere: toma la unidad y no la libera nunca.
        LockKey key = LockKey.of("closing", "unit", run.getRunId(), unidad.getUnitKey());
        var handle = lock.tryAcquire(key, Duration.ofMillis(600)).orElseThrow();
        units.claim(unidad.getUnitId(), "pod-que-muere", handle.fencingToken(),
                Instant.now().minusSeconds(1));       // arrendamiento ya vencido

        assertThat(units.countByStatus(run.getRunId(), UnitStatus.CLAIMED.name())).isEqualTo(1);

        // Mientras el candado de Redis siga vivo, el reaper NO la toca: el pod podría estar vivo.
        assertThat(reaper.reap(10)).as("candado vivo: se respeta").isZero();

        try { Thread.sleep(800); } catch (InterruptedException ignored) { }

        assertThat(reaper.reap(10)).as("vencido el candado, se devuelve").isEqualTo(1);
        assertThat(units.countByStatus(run.getRunId(), UnitStatus.PENDING.name())).isEqualTo(1);

        // Y otro pod la termina.
        worker.workBatch(run.getRunId(), fecha, procesadorQue(u -> { }), 0);
        assertThat(units.countByStatus(run.getRunId(), UnitStatus.DONE.name())).isEqualTo(1);
    }

    // ── Fencing ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("el titular de un candado vencido no puede escribir: fencing token")
    void elTokenViejoSeRechaza() {
        // El escenario que un TTL no cubre: el candado expira con el trabajo todavía vivo —una
        // pausa de GC basta— y dos procesos se creen dueños. El número monótono los distingue.
        CloseRun run = planificar(1);
        CloseUnit unidad = units.findPending(run.getRunId(), 0, 1).get(0);

        units.claim(unidad.getUnitId(), "pod-nuevo", 50L, Instant.now().plusSeconds(300));
        CloseUnit recargada = units.findById(unidad.getUnitId()).orElseThrow();

        assertThatThrownBy(() -> recargada.markDone(7L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("anterior al titular actual");

        // El titular actual sí puede.
        recargada.markDone(50L);
        assertThat(recargada.status()).isEqualTo(UnitStatus.DONE);
    }

    // ── Sello ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("no se sella una corrida con unidades fallidas")
    void noSeSellaAMedias() {
        // Un cierre que sella sobre datos incompletos produce un número que parece bueno y no lo
        // es. Es el modo de falla que este sistema existe para evitar.
        CloseRun run = planificar(2);
        run.progress(1, 1, 0);

        assertThatThrownBy(run::seal)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unidades fallidas");

        run.progress(2, 0, 0);
        run.seal();
        assertThat(run.isSealed()).isTrue();
    }

    @Test
    @DisplayName("re-correr el mismo día no reprocesa nada")
    void reCorrerNoReprocesa() {
        CloseRun run = planificar(4);
        AtomicInteger vueltas = new AtomicInteger();
        var procesador = procesadorQue(u -> vueltas.incrementAndGet());

        worker.workBatch(run.getRunId(), fecha, procesador, 0);
        assertThat(vueltas.get()).isEqualTo(4);

        worker.workBatch(run.getRunId(), fecha, procesador, 0);
        assertThat(vueltas.get()).as("la segunda corrida no encuentra PENDING").isEqualTo(4);
    }
}

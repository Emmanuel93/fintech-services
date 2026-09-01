package com.fintech.shared.lock;

import com.fintech.shared.lock.redis.RedisDistributedLock;
import com.fintech.shared.lock.redisson.RedissonDistributedLock;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El candado contra un Redis de verdad.
 *
 * <p>Se corre <b>la misma batería sobre los dos proveedores</b>. Que ambos pasen es lo que sostiene
 * la promesa del puerto: cambiar de implementación es una propiedad, no una migración. Si un día
 * uno deja de cumplir el contrato, esta prueba lo dice — no se descubre en el cierre de un martes.
 */
class DistributedLockIT {

    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    static RedissonClient redissonClient;
    static LettuceConnectionFactory lettuce;

    @BeforeAll
    static void startRedis() {
        REDIS.start();
        String host = REDIS.getHost();
        Integer port = REDIS.getMappedPort(6379);

        Config config = new Config();
        config.useSingleServer().setAddress("redis://" + host + ":" + port);
        redissonClient = Redisson.create(config);

        lettuce = new LettuceConnectionFactory(host, port);
        lettuce.afterPropertiesSet();
    }

    @AfterAll
    static void stopRedis() {
        if (redissonClient != null) redissonClient.shutdown();
        if (lettuce != null) lettuce.destroy();
        REDIS.stop();
    }

    static Stream<org.junit.jupiter.params.provider.Arguments> proveedores() {
        StringRedisTemplate template = new StringRedisTemplate(lettuce);
        return Stream.of(
                org.junit.jupiter.params.provider.Arguments.of("redisson",
                        new RedissonDistributedLock(redissonClient, new LockProperties())),
                org.junit.jupiter.params.provider.Arguments.of("redis-template",
                        new RedisDistributedLock(template)));
    }

    private static LockKey nuevaLlave() {
        return LockKey.of("test", "unit", UUID.randomUUID().toString());
    }

    // ── El requisito literal: el primero entra, los demás no esperan ──────────

    @ParameterizedTest(name = "[{0}] el primero entra y el segundo NO")
    @MethodSource("proveedores")
    @DisplayName("múltiples peticiones sobre la misma llave: sólo la primera se procesa")
    void soloElPrimeroEntra(String nombre, DistributedLock lock) {
        LockKey key = nuevaLlave();

        Optional<LockHandle> primero = lock.tryAcquire(key, Duration.ofSeconds(30));
        Optional<LockHandle> segundo = lock.tryAcquire(key, Duration.ofSeconds(30));

        assertThat(primero).as("[%s] el primero entra", nombre).isPresent();
        assertThat(segundo).as("[%s] el segundo se descarta, no espera", nombre).isEmpty();

        lock.release(primero.orElseThrow());
    }

    @ParameterizedTest(name = "[{0}] tras liberar, el siguiente entra")
    @MethodSource("proveedores")
    void trasLiberarEntraElSiguiente(String nombre, DistributedLock lock) {
        LockKey key = nuevaLlave();

        LockHandle primero = lock.tryAcquire(key, Duration.ofSeconds(30)).orElseThrow();
        lock.release(primero);

        Optional<LockHandle> segundo = lock.tryAcquire(key, Duration.ofSeconds(30));
        assertThat(segundo).as("[%s]", nombre).isPresent();
        lock.release(segundo.orElseThrow());
    }

    @ParameterizedTest(name = "[{0}] llaves distintas no se estorban")
    @MethodSource("proveedores")
    void llavesDistintasNoSeEstorban(String nombre, DistributedLock lock) {
        LockHandle a = lock.tryAcquire(nuevaLlave(), Duration.ofSeconds(30)).orElseThrow();
        LockHandle b = lock.tryAcquire(nuevaLlave(), Duration.ofSeconds(30)).orElseThrow();

        assertThat(a.key()).as("[%s]", nombre).isNotEqualTo(b.key());
        lock.release(a);
        lock.release(b);
    }

    // ── La llave de N segundos: el pod que muere no atasca el sistema ─────────

    @ParameterizedTest(name = "[{0}] al vencer el TTL, otro entra sin intervención")
    @MethodSource("proveedores")
    @DisplayName("el pod muere sin liberar: el TTL suelta el candado solo")
    void elTtlSueltaElCandado(String nombre, DistributedLock lock) throws Exception {
        LockKey key = nuevaLlave();

        // Se toma y NUNCA se libera: es el pod que muere a media corrida.
        assertThat(lock.tryAcquire(key, Duration.ofMillis(800))).isPresent();
        assertThat(lock.tryAcquire(key, Duration.ofSeconds(30)))
                .as("[%s] mientras el TTL vive, nadie más entra", nombre).isEmpty();

        Thread.sleep(1_100);

        Optional<LockHandle> despues = lock.tryAcquire(key, Duration.ofSeconds(5));
        assertThat(despues).as("[%s] vencido el TTL, otro pod entra", nombre).isPresent();
        lock.release(despues.orElseThrow());
    }

    // ── Fencing: la defensa contra el titular que ya no lo es ─────────────────

    @ParameterizedTest(name = "[{0}] el fencing token crece en cada adquisición")
    @MethodSource("proveedores")
    @DisplayName("el fencing token es estrictamente creciente y sobrevive a la liberación")
    void fencingEstrictamenteCreciente(String nombre, DistributedLock lock) {
        LockKey key = nuevaLlave();

        long t1 = tomarYSoltar(lock, key);
        long t2 = tomarYSoltar(lock, key);
        long t3 = tomarYSoltar(lock, key);

        // Si el contador viviera en la llave del candado, el borrado de la liberación lo
        // reiniciaría y los tres números serían el mismo.
        assertThat(List.of(t1, t2, t3)).as("[%s]", nombre).isSorted();
        assertThat(t1).as("[%s] estrictamente creciente", nombre).isLessThan(t3);
    }

    private long tomarYSoltar(DistributedLock lock, LockKey key) {
        LockHandle h = lock.tryAcquire(key, Duration.ofSeconds(10)).orElseThrow();
        long token = h.fencingToken();
        lock.release(h);
        return token;
    }

    // ── Exclusión real bajo concurrencia ─────────────────────────────────────

    @ParameterizedTest(name = "[{0}] 32 hilos, una llave: exclusión estricta")
    @MethodSource("proveedores")
    @DisplayName("bajo concurrencia real, dos hilos nunca están dentro a la vez")
    void exclusionBajoConcurrencia(String nombre, DistributedLock lock) throws Exception {
        LockKey key = nuevaLlave();
        int hilos = 32;

        AtomicInteger dentroAhora = new AtomicInteger();
        AtomicInteger maxSimultaneos = new AtomicInteger();
        AtomicInteger ejecuciones = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(hilos);
        CountDownLatch salida = new CountDownLatch(1);
        CountDownLatch fin = new CountDownLatch(hilos);

        for (int i = 0; i < hilos; i++) {
            pool.submit(() -> {
                try {
                    salida.await();
                    lock.withLock(key, Duration.ofSeconds(10), () -> {
                        int ahora = dentroAhora.incrementAndGet();
                        maxSimultaneos.accumulateAndGet(ahora, Math::max);
                        ejecuciones.incrementAndGet();
                        try { Thread.sleep(15); } catch (InterruptedException ignored) { }
                        dentroAhora.decrementAndGet();
                        return null;
                    });
                } catch (Exception ignored) {
                    // "ocupado" es un resultado normal; no falla la prueba
                } finally {
                    fin.countDown();
                }
            });
        }

        salida.countDown();
        assertThat(fin.await(60, TimeUnit.SECONDS)).as("[%s] terminan a tiempo", nombre).isTrue();
        pool.shutdownNow();

        assertThat(maxSimultaneos.get())
                .as("[%s] nunca hubo dos hilos dentro de la sección crítica", nombre)
                .isEqualTo(1);
        assertThat(ejecuciones.get())
                .as("[%s] al menos uno hizo el trabajo", nombre)
                .isGreaterThanOrEqualTo(1);
    }

    @ParameterizedTest(name = "[{0}] withLock libera aunque el trabajo lance")
    @MethodSource("proveedores")
    void withLockLiberaAnteExcepcion(String nombre, DistributedLock lock) {
        LockKey key = nuevaLlave();

        try {
            lock.withLock(key, Duration.ofSeconds(30), () -> { throw new IllegalStateException("boom"); });
        } catch (IllegalStateException esperado) {
            // se propaga a propósito
        }

        Optional<LockHandle> siguiente = lock.tryAcquire(key, Duration.ofSeconds(5));
        assertThat(siguiente).as("[%s] el candado quedó libre pese a la excepción", nombre).isPresent();
        lock.release(siguiente.orElseThrow());
    }
}

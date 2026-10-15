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

    static Stream<org.junit.jupiter.params.provider.Arguments> providers() {
        StringRedisTemplate template = new StringRedisTemplate(lettuce);
        return Stream.of(
                org.junit.jupiter.params.provider.Arguments.of("redisson",
                        new RedissonDistributedLock(redissonClient, new LockProperties())),
                org.junit.jupiter.params.provider.Arguments.of("redis-template",
                        new RedisDistributedLock(template)));
    }

    private static LockKey newKey() {
        return LockKey.of("test", "unit", UUID.randomUUID().toString());
    }

    // ── El requisito literal: el primero entra, los demás no esperan ──────────

    @ParameterizedTest(name = "[{0}] el primero entra y el segundo NO")
    @MethodSource("providers")
    @DisplayName("múltiples peticiones sobre la misma llave: sólo la primera se procesa")
    void soloElPrimeroEntra(String name, DistributedLock lock) {
        LockKey key = newKey();

        Optional<LockHandle> first = lock.tryAcquire(key, Duration.ofSeconds(30));
        Optional<LockHandle> segundo = lock.tryAcquire(key, Duration.ofSeconds(30));

        assertThat(first).as("[%s] el primero entra", name).isPresent();
        assertThat(segundo).as("[%s] el segundo se descarta, no espera", name).isEmpty();

        lock.release(first.orElseThrow());
    }

    @ParameterizedTest(name = "[{0}] tras liberar, el siguiente entra")
    @MethodSource("providers")
    void trasLiberarEntraElSiguiente(String name, DistributedLock lock) {
        LockKey key = newKey();

        LockHandle first = lock.tryAcquire(key, Duration.ofSeconds(30)).orElseThrow();
        lock.release(first);

        Optional<LockHandle> segundo = lock.tryAcquire(key, Duration.ofSeconds(30));
        assertThat(segundo).as("[%s]", name).isPresent();
        lock.release(segundo.orElseThrow());
    }

    @ParameterizedTest(name = "[{0}] llaves distintas no se estorban")
    @MethodSource("providers")
    void llavesDistintasNoSeEstorban(String name, DistributedLock lock) {
        LockHandle a = lock.tryAcquire(newKey(), Duration.ofSeconds(30)).orElseThrow();
        LockHandle b = lock.tryAcquire(newKey(), Duration.ofSeconds(30)).orElseThrow();

        assertThat(a.key()).as("[%s]", name).isNotEqualTo(b.key());
        lock.release(a);
        lock.release(b);
    }

    // ── La llave de N segundos: el pod que muere no atasca el sistema ─────────

    @ParameterizedTest(name = "[{0}] al vencer el TTL, otro entra sin intervención")
    @MethodSource("providers")
    @DisplayName("el pod muere sin liberar: el TTL suelta el candado solo")
    void elTtlSueltaElCandado(String name, DistributedLock lock) throws Exception {
        LockKey key = newKey();

        // Se toma y NUNCA se libera: es el pod que muere a media corrida.
        assertThat(lock.tryAcquire(key, Duration.ofMillis(800))).isPresent();
        assertThat(lock.tryAcquire(key, Duration.ofSeconds(30)))
                .as("[%s] mientras el TTL vive, nadie más entra", name).isEmpty();

        Thread.sleep(1_100);

        Optional<LockHandle> despues = lock.tryAcquire(key, Duration.ofSeconds(5));
        assertThat(despues).as("[%s] vencido el TTL, otro pod entra", name).isPresent();
        lock.release(despues.orElseThrow());
    }

    // ── Fencing: la defensa contra el titular que ya no lo es ─────────────────

    @ParameterizedTest(name = "[{0}] el fencing token crece en cada adquisición")
    @MethodSource("providers")
    @DisplayName("el fencing token es estrictamente creciente y sobrevive a la liberación")
    void fencingEstrictamenteCreciente(String name, DistributedLock lock) {
        LockKey key = newKey();

        long t1 = acquireAndRelease(lock, key);
        long t2 = acquireAndRelease(lock, key);
        long t3 = acquireAndRelease(lock, key);

        // Si el contador viviera en la llave del candado, el borrado de la liberación lo
        // reiniciaría y los tres números serían el mismo.
        assertThat(List.of(t1, t2, t3)).as("[%s]", name).isSorted();
        assertThat(t1).as("[%s] estrictamente creciente", name).isLessThan(t3);
    }

    private long acquireAndRelease(DistributedLock lock, LockKey key) {
        LockHandle h = lock.tryAcquire(key, Duration.ofSeconds(10)).orElseThrow();
        long token = h.fencingToken();
        lock.release(h);
        return token;
    }

    // ── Exclusión real bajo concurrencia ─────────────────────────────────────

    @ParameterizedTest(name = "[{0}] 32 hilos, una llave: exclusión estricta")
    @MethodSource("providers")
    @DisplayName("bajo concurrencia real, dos hilos nunca están dentro a la vez")
    void exclusionBajoConcurrencia(String name, DistributedLock lock) throws Exception {
        LockKey key = newKey();
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
        assertThat(fin.await(60, TimeUnit.SECONDS)).as("[%s] terminan a tiempo", name).isTrue();
        pool.shutdownNow();

        assertThat(maxSimultaneos.get())
                .as("[%s] nunca hubo dos hilos dentro de la sección crítica", name)
                .isEqualTo(1);
        assertThat(ejecuciones.get())
                .as("[%s] al menos uno hizo el trabajo", name)
                .isGreaterThanOrEqualTo(1);
    }

    @ParameterizedTest(name = "[{0}] withLock libera aunque el trabajo lance")
    @MethodSource("providers")
    void withLockLiberaAnteExcepcion(String name, DistributedLock lock) {
        LockKey key = newKey();

        try {
            lock.withLock(key, Duration.ofSeconds(30), () -> { throw new IllegalStateException("boom"); });
        } catch (IllegalStateException esperado) {
            // se propaga a propósito
        }

        Optional<LockHandle> next = lock.tryAcquire(key, Duration.ofSeconds(5));
        assertThat(next).as("[%s] el candado quedó libre pese a la excepción", name).isPresent();
        lock.release(next.orElseThrow());
    }
}

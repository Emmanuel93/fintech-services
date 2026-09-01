package com.fintech.shared.lock;

import com.fintech.shared.lock.redisson.RedissonDistributedLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RedissonDistributedLockTest {

    @Mock RedissonClient redisson;
    @Mock RLock rlock;
    @Mock RAtomicLong fence;

    LockProperties properties;
    RedissonDistributedLock lock;
    LockKey key;

    @BeforeEach
    void setUp() {
        properties = new LockProperties();
        key = LockKey.of("closing", "unit", "run-1", "cuenta-9");
        when(redisson.getLock(anyString())).thenReturn(rlock);
        when(redisson.getSpinLock(anyString())).thenReturn(rlock);
        when(redisson.getFairLock(anyString())).thenReturn(rlock);
        when(redisson.getAtomicLong(anyString())).thenReturn(fence);
        lock = new RedissonDistributedLock(redisson, properties);
    }

    @Test
    @DisplayName("el primero entra: tryLock con waitTime 0 y el TTL pedido")
    void elPrimeroEntra() throws Exception {
        when(rlock.tryLock(eq(0L), eq(30_000L), eq(TimeUnit.MILLISECONDS))).thenReturn(true);
        when(fence.incrementAndGet()).thenReturn(1L);

        Optional<LockHandle> handle = lock.tryAcquire(key, Duration.ofSeconds(30));

        assertThat(handle).isPresent();
        assertThat(handle.get().fencingToken()).isEqualTo(1L);
        // waitTime 0: nadie espera. El que no gana sigue con la siguiente unidad.
        verify(rlock).tryLock(0L, 30_000L, TimeUnit.MILLISECONDS);
    }

    @Test
    @DisplayName("el segundo NO entra y no consume número de fencing")
    void elSegundoNoEntra() throws Exception {
        when(rlock.tryLock(anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS))).thenReturn(false);

        assertThat(lock.tryAcquire(key, Duration.ofSeconds(30))).isEmpty();

        // El contador cuenta adquisiciones, no intentos.
        verify(fence, never()).incrementAndGet();
    }

    @Test
    @DisplayName("el fencing token crece en cada adquisición")
    void fencingCreciente() throws Exception {
        when(rlock.tryLock(anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS))).thenReturn(true);
        when(fence.incrementAndGet()).thenReturn(1L, 2L, 3L);

        long a = lock.tryAcquire(key, Duration.ofSeconds(5)).orElseThrow().fencingToken();
        long b = lock.tryAcquire(key, Duration.ofSeconds(5)).orElseThrow().fencingToken();
        long c = lock.tryAcquire(key, Duration.ofSeconds(5)).orElseThrow().fencingToken();

        assertThat(a).isLessThan(b);
        assertThat(b).isLessThan(c);
    }

    @Test
    @DisplayName("flavor STANDARD usa getLock; SPIN usa getSpinLock; FAIR usa getFairLock")
    void elFlavorEligeLaFabrica() throws Exception {
        when(rlock.tryLock(anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS))).thenReturn(true);
        when(fence.incrementAndGet()).thenReturn(1L);

        lock.tryAcquire(key, Duration.ofSeconds(5));
        verify(redisson).getLock(key.value());

        properties.setFlavor(LockProperties.Flavor.SPIN);
        lock.tryAcquire(key, Duration.ofSeconds(5));
        verify(redisson).getSpinLock(key.value());

        properties.setFlavor(LockProperties.Flavor.FAIR);
        lock.tryAcquire(key, Duration.ofSeconds(5));
        verify(redisson).getFairLock(key.value());
    }

    @Test
    @DisplayName("modo watchdog: se toma sin TTL fijo y Redisson renueva mientras el hilo viva")
    void modoWatchdog() throws Exception {
        when(rlock.tryLock(eq(0L), eq(TimeUnit.MILLISECONDS))).thenReturn(true);
        when(fence.incrementAndGet()).thenReturn(1L);

        assertThat(lock.tryAcquire(key, RedissonDistributedLock.WATCHDOG)).isPresent();

        verify(rlock).tryLock(0L, TimeUnit.MILLISECONDS);
    }

    @Test
    @DisplayName("libera sólo si el hilo actual es el titular")
    void liberaSoloElTitular() {
        when(rlock.isHeldByCurrentThread()).thenReturn(false);
        lock.release(new LockHandle(key, "hilo-x", 1L, java.time.Instant.now()));

        // forceUnlock() liberaría el candado de otro: exactamente lo que hay que evitar.
        verify(rlock, never()).unlock();
        verify(rlock, never()).forceUnlock();
    }

    @Test
    @DisplayName("libera cuando sí es el titular")
    void liberaSiendoTitular() {
        when(rlock.isHeldByCurrentThread()).thenReturn(true);
        lock.release(new LockHandle(key, "hilo-x", 1L, java.time.Instant.now()));
        verify(rlock).unlock();
    }

    @Test
    @DisplayName("Redis caído propaga LockAcquisitionException; no se corre sin candado")
    void redisCaido() throws Exception {
        when(rlock.tryLock(anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS)))
                .thenThrow(new org.redisson.client.RedisConnectionException("sin conexión"));

        assertThatThrownBy(() -> lock.tryAcquire(key, Duration.ofSeconds(30)))
                .isInstanceOf(LockAcquisitionException.class)
                .hasMessageContaining(key.value());
    }

    @Test
    @DisplayName("TTL no positivo se rechaza antes de tocar Redis")
    void ttlInvalido() {
        assertThatThrownBy(() -> lock.tryAcquire(key, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("la re-entrada del mismo hilo se rechaza: no es un candado reentrante")
    void rechazaLaReentrada() throws Exception {
        // RLock es reentrante por diseño. Aquí eso rompería la promesa de "la primera se procesa
        // y las siguientes se descartan": una segunda petición servida por el mismo hilo del pool
        // pasaría de largo.
        // Se detecta ANTES de tomar: deshacerla después con unlock() reestablecería el
        // arrendamiento al valor por defecto de Redisson en vez de al TTL pedido.
        when(rlock.isHeldByCurrentThread()).thenReturn(true);

        assertThat(lock.tryAcquire(key, Duration.ofSeconds(30))).isEmpty();

        verify(rlock, never()).tryLock(anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS));
        verify(fence, never()).incrementAndGet();     // no consume número de fencing
    }

    @Test
    @DisplayName("withLock no ejecuta el trabajo si el candado está tomado")
    void withLockNoEjecutaSiOcupado() throws Exception {
        when(rlock.tryLock(anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS))).thenReturn(false);

        boolean[] ejecutado = { false };
        Optional<String> r = lock.withLock(key, Duration.ofSeconds(5), () -> {
            ejecutado[0] = true;
            return "hecho";
        });

        assertThat(r).isEmpty();
        assertThat(ejecutado[0]).isFalse();
    }
}

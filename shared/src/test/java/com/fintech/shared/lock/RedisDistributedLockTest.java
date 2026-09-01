package com.fintech.shared.lock;

import com.fintech.shared.lock.redis.RedisDistributedLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RedisDistributedLockTest {

    @Mock StringRedisTemplate redis;
    @Mock ValueOperations<String, String> valueOps;

    RedisDistributedLock lock;
    LockKey key;

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(valueOps);
        lock = new RedisDistributedLock(redis);
        key  = LockKey.of("closing", "unit", "run-1", "cuenta-9");
    }

    @Test
    @DisplayName("adquiere con SET NX PX y devuelve el fencing token del contador")
    void adquiere() {
        when(valueOps.setIfAbsent(eq(key.value()), anyString(), eq(Duration.ofSeconds(30))))
                .thenReturn(true);
        when(valueOps.increment(key.fenceKey())).thenReturn(7L);

        Optional<LockHandle> handle = lock.tryAcquire(key, Duration.ofSeconds(30));

        assertThat(handle).isPresent();
        assertThat(handle.get().fencingToken()).isEqualTo(7L);
        assertThat(handle.get().token()).isNotBlank();
        assertThat(handle.get().key()).isEqualTo(key);
    }

    @Test
    @DisplayName("candado ocupado devuelve vacío y NO incrementa el fencing")
    void ocupadoNoIncrementa() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);

        assertThat(lock.tryAcquire(key, Duration.ofSeconds(30))).isEmpty();

        // El contador cuenta adquisiciones, no intentos: si contara intentos, dos pods compitiendo
        // lo dispararían sin que ninguno hiciera trabajo.
        verify(valueOps, never()).increment(anyString());
    }

    @Test
    @DisplayName("cada adquisición usa un token distinto")
    void tokenPorAdquisicion() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(valueOps.increment(anyString())).thenReturn(1L, 2L);

        String t1 = lock.tryAcquire(key, Duration.ofSeconds(5)).orElseThrow().token();
        String t2 = lock.tryAcquire(key, Duration.ofSeconds(5)).orElseThrow().token();

        assertThat(t1).isNotEqualTo(t2);
    }

    /** Hace que `execute(SessionCallback)` corra de verdad el callback contra un RedisOperations falso. */
    private void stubSession(String tokenEnRedis, boolean execOk) {
        when(redis.execute(any(SessionCallback.class))).thenAnswer(inv -> {
            SessionCallback<Object> cb = inv.getArgument(0);
            RedisOperations<String, String> ops = org.mockito.Mockito.mock(RedisOperations.class);
            ValueOperations<String, String> vo = org.mockito.Mockito.mock(ValueOperations.class);
            when(ops.opsForValue()).thenReturn(vo);
            when(vo.get(anyString())).thenReturn(tokenEnRedis);
            when(ops.exec()).thenReturn(execOk ? List.of((Object) 1L) : List.of());
            return cb.execute(ops);
        });
    }

    @Test
    @DisplayName("libera comparando el token dentro de WATCH/MULTI/EXEC")
    void liberaConWatch() {
        LockHandle handle = new LockHandle(key, "token-abc", 1L, java.time.Instant.now().plusSeconds(30));
        stubSession("token-abc", true);

        lock.release(handle);

        verify(redis).execute(any(SessionCallback.class));
    }

    @Test
    @DisplayName("liberar un candado ajeno no borra nada y no lanza")
    void liberacionAjena() {
        LockHandle handle = new LockHandle(key, "token-viejo", 1L, java.time.Instant.now());
        // En Redis vive el token de OTRO titular: la comparación falla y no se toca la llave.
        stubSession("token-de-otro", true);

        lock.release(handle);   // no lanza: el TTL acabará soltando el candado igual
    }

    @Test
    @DisplayName("si la llave cambia entre WATCH y EXEC, la transacción se aborta")
    void abortaSiLaLlaveCambia() {
        LockHandle handle = new LockHandle(key, "token-abc", 1L, java.time.Instant.now().plusSeconds(30));
        // exec() vacío es como Redis avisa que WATCH detectó una modificación concurrente.
        stubSession("token-abc", false);

        assertThat(lock.extend(handle, Duration.ofSeconds(30))).isFalse();
    }

    @Test
    @DisplayName("extender devuelve false cuando ya no se es el titular")
    void extenderPerdido() {
        LockHandle handle = new LockHandle(key, "token-viejo", 1L, java.time.Instant.now());
        stubSession("token-de-otro", true);

        assertThat(lock.extend(handle, Duration.ofSeconds(30))).isFalse();
    }

    @Test
    @DisplayName("extender renueva cuando se sigue siendo el titular")
    void extenderOk() {
        LockHandle handle = new LockHandle(key, "token-abc", 1L, java.time.Instant.now().plusSeconds(5));
        stubSession("token-abc", true);

        assertThat(lock.extend(handle, Duration.ofSeconds(30))).isTrue();
    }

    @Test
    @DisplayName("Redis caído propaga LockAcquisitionException; no se degrada a correr sin candado")
    void redisCaido() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new RedisConnectionFailureException("sin conexión"));

        assertThatThrownBy(() -> lock.tryAcquire(key, Duration.ofSeconds(30)))
                .isInstanceOf(LockAcquisitionException.class)
                .hasMessageContaining(key.value());
    }

    @Test
    @DisplayName("TTL no positivo se rechaza antes de tocar Redis")
    void ttlInvalido() {
        assertThatThrownBy(() -> lock.tryAcquire(key, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> lock.tryAcquire(key, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("withLock no ejecuta el trabajo si el candado está tomado")
    void withLockNoEjecuta() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);

        boolean[] ejecutado = { false };
        Optional<String> r = lock.withLock(key, Duration.ofSeconds(5), () -> {
            ejecutado[0] = true;
            return "hecho";
        });

        assertThat(r).isEmpty();
        assertThat(ejecutado[0]).isFalse();
    }

    @Test
    @DisplayName("withLock libera aunque el trabajo lance")
    void withLockLiberaAnteExcepcion() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(valueOps.increment(anyString())).thenReturn(1L);
        stubSession("cualquiera", true);

        assertThatThrownBy(() -> lock.withLock(key, Duration.ofSeconds(5), () -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);

        // Lo que importa: se intentó liberar pese a la excepción.
        verify(redis).execute(any(SessionCallback.class));
    }
}

package com.fintech.shared.lock.redis;

import com.fintech.shared.lock.DistributedLock;
import com.fintech.shared.lock.LockAcquisitionException;
import com.fintech.shared.lock.LockHandle;
import com.fintech.shared.lock.LockKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Candado distribuido sobre Redis, con la API de Spring Data Redis.
 *
 * <p><b>Por qué la liberación no es un {@code DEL} directo.</b> Entre leer el token y borrar la
 * llave, el TTL puede vencer y otro proceso tomar el candado: un {@code DEL} a ciegas le borraría
 * el suyo y dos procesos entrarían a la vez. La comparación y el borrado tienen que ser un solo
 * paso indivisible.
 *
 * <p>Eso se resuelve con la transacción optimista de Redis —{@code WATCH} / {@code MULTI} /
 * {@code EXEC}— dentro de un {@link SessionCallback}, que garantiza que los tres comandos viajan
 * por la <b>misma conexión</b>. {@code WATCH} vigila la llave: si alguien la toca entre el
 * {@code WATCH} y el {@code EXEC}, la transacción se aborta sola y {@code exec()} devuelve vacío.
 * Es la misma garantía que daría un script, escrita en Java y sin salir de Spring Data Redis.
 *
 * <p><b>Por qué el contador de fencing se incrementa después de adquirir.</b> Sólo el que ganó
 * incrementa, así que el número identifica adquisiciones y no intentos. Vive en su propia llave
 * para que el borrado de la liberación no lo reinicie — si compartiera llave con el candado, dos
 * titulares sucesivos recibirían el mismo número y el mecanismo no serviría para nada.
 */
public class RedisDistributedLock implements DistributedLock {

    private static final Logger log = LoggerFactory.getLogger(RedisDistributedLock.class);

    private final StringRedisTemplate redis;
    private final Clock clock;

    public RedisDistributedLock(StringRedisTemplate redis) {
        this(redis, Clock.systemUTC());
    }

    public RedisDistributedLock(StringRedisTemplate redis, Clock clock) {
        this.redis = redis;
        this.clock = clock;
    }

    @Override
    public Optional<LockHandle> tryAcquire(LockKey key, Duration ttl) {
        requirePositive(ttl);
        String token = UUID.randomUUID().toString();
        try {
            // SET key token NX PX ttl — un solo comando, atómico por definición.
            Boolean acquired = redis.opsForValue().setIfAbsent(key.value(), token, ttl);
            if (!Boolean.TRUE.equals(acquired)) {
                log.trace("Candado ocupado key={}", key);
                return Optional.empty();
            }
            Long fencing = redis.opsForValue().increment(key.fenceKey());
            LockHandle handle = new LockHandle(key, token,
                    fencing != null ? fencing : 0L, Instant.now(clock).plus(ttl));
            log.debug("Candado tomado key={} fencing={} ttl={}", key, handle.fencingToken(), ttl);
            return Optional.of(handle);
        } catch (RuntimeException ex) {
            // Redis no disponible. No se degrada a "seguir sin candado": en un proceso financiero
            // correr sin exclusión es peor que no correr.
            throw new LockAcquisitionException(key, ex);
        }
    }

    @Override
    public boolean extend(LockHandle handle, Duration ttl) {
        requirePositive(ttl);
        try {
            return compareAndRun(handle, ops -> ops.expire(handle.key().value(), ttl));
        } catch (RuntimeException ex) {
            throw new LockAcquisitionException(handle.key(), ex);
        }
    }

    @Override
    public void release(LockHandle handle) {
        try {
            if (!compareAndRun(handle, ops -> ops.delete(handle.key().value()))) {
                // El TTL venció y otro pudo haber tomado el candado. Es exactamente el escenario
                // para el que existe el fencing token: el trabajo de este titular ya no es de fiar.
                log.warn("Liberación sin efecto key={} — el candado ya había expirado o cambió de dueño",
                        handle.key());
            }
        } catch (RuntimeException ex) {
            // Liberar nunca debe tumbar al llamador: el TTL acabará soltando el candado igual.
            log.error("Fallo al liberar el candado key={}: {}", handle.key(), ex.getMessage());
        }
    }

    /**
     * Ejecuta {@code accion} sobre la llave <b>sólo si</b> seguimos siendo el titular, de forma
     * atómica.
     *
     * <p>{@code WATCH} marca la llave; si cambia antes del {@code EXEC}, Redis aborta la transacción
     * y devuelve una lista vacía. Así, la comparación del token y la escritura no pueden separarse.
     *
     * @return {@code true} si la acción se ejecutó; {@code false} si el candado ya no era nuestro.
     */
    private boolean compareAndRun(LockHandle handle, java.util.function.Consumer<RedisOperations<String, String>> accion) {
        String llave = handle.key().value();

        @SuppressWarnings("unchecked")
        List<Object> resultado = redis.execute(new SessionCallback<>() {
            @Override
            @SuppressWarnings("unchecked")
            public List<Object> execute(RedisOperations operations) {
                RedisOperations<String, String> ops = (RedisOperations<String, String>) operations;

                ops.watch(llave);
                String actual = ops.opsForValue().get(llave);
                if (!handle.token().equals(actual)) {
                    ops.unwatch();
                    return null;              // ya no somos el titular: no se toca nada
                }

                ops.multi();
                accion.accept(ops);
                return ops.exec();            // vacío ⇒ la llave cambió entre WATCH y EXEC
            }
        });

        return resultado != null && !resultado.isEmpty();
    }

    private static void requirePositive(Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("El TTL del candado debe ser positivo: " + ttl);
        }
    }
}

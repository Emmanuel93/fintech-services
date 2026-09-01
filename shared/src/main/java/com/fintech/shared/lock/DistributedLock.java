package com.fintech.shared.lock;

import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Exclusión mutua entre procesos, para todo el monorepo.
 *
 * <p><b>Qué garantiza y qué no.</b> Garantiza que dos réplicas no hagan el mismo trabajo a la vez.
 * <b>No</b> garantiza corrección por sí solo: un candado con expiración es exclusión
 * <i>best-effort</i>, y siempre existe la ventana en la que el TTL vence con el trabajo todavía
 * vivo. Por eso toda escritura protegida por un candado tiene que ser además idempotente por su
 * clave natural, y por eso el handle trae un {@link LockHandle#fencingToken()}.
 *
 * <p>La regla operativa: <b>el candado evita el trabajo duplicado; la clave natural evita el dato
 * duplicado.</b> Quitar el candado debe degradar el rendimiento, nunca la corrección.
 */
public interface DistributedLock {

    /** @return el handle, o vacío si otro lo tiene. Vacío es un resultado normal, no un error. */
    Optional<LockHandle> tryAcquire(LockKey key, Duration ttl);

    /** Renueva el TTL sólo si se sigue siendo el titular. @return false si ya se perdió. */
    boolean extend(LockHandle handle, Duration ttl);

    /** Libera sólo si se sigue siendo el titular. Nunca lanza: liberar es siempre seguro. */
    void release(LockHandle handle);

    /**
     * Ejecuta {@code work} con el candado tomado y lo libera pase lo que pase.
     *
     * @return el resultado, o vacío si el candado estaba tomado — en cuyo caso {@code work}
     *         <b>no se ejecutó</b>.
     */
    default <T> Optional<T> withLock(LockKey key, Duration ttl, Supplier<T> work) {
        Optional<LockHandle> handle = tryAcquire(key, ttl);
        if (handle.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(work.get());
        } finally {
            release(handle.get());
        }
    }

    /** Variante para trabajo sin resultado. @return true si se ejecutó. */
    default boolean runWithLock(LockKey key, Duration ttl, Runnable work) {
        return withLock(key, ttl, () -> { work.run(); return Boolean.TRUE; }).isPresent();
    }
}

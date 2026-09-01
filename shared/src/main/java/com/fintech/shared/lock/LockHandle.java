package com.fintech.shared.lock;

import java.time.Clock;
import java.time.Instant;

/**
 * La prueba de posesión de un candado.
 *
 * @param token        secreto de esta adquisición. Sin él, cualquiera podría liberar el candado de
 *                     otro: la liberación compara el token antes de borrar.
 * @param fencingToken número monótono creciente por llave. Es la defensa contra el escenario que un
 *                     TTL no cubre — el candado expira mientras el trabajo sigue vivo (pausa de GC,
 *                     red lenta) y dos procesos se creen dueños a la vez. El titular viejo llega con
 *                     un número menor al último visto y <b>su escritura se rechaza</b>. Sin esto, el
 *                     candado da exclusión pero no corrección.
 */
public record LockHandle(LockKey key, String token, long fencingToken, Instant expiresAt) {

    public boolean isExpired(Clock clock) {
        return !Instant.now(clock).isBefore(expiresAt);
    }
}

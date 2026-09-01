package com.fintech.closing;

import com.fintech.shared.lock.DistributedLock;
import com.fintech.shared.lock.LockHandle;
import com.fintech.shared.lock.LockKey;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Candado en memoria, <b>sólo para pruebas que no van sobre el reparto</b>.
 *
 * <p>Existe porque el servicio de cierres <b>no arranca sin candado</b>, y eso es deliberado: correr
 * un cierre sin exclusión es peor que no correrlo. Las pruebas de esquema y de calendario no
 * necesitan Redis, pero sí necesitan que el contexto levante.
 *
 * <p><b>No sirve para nada más.</b> Es exclusión dentro de una sola JVM: no coordina réplicas.
 * El reparto de verdad se prueba en {@code MotorDeCorridasIT} contra un Redis real, que es donde
 * salieron los dos fallos que los mocks no ven.
 */
@TestConfiguration
public class InMemoryLockConfig {

    @Bean
    public DistributedLock inMemoryLock() {
        return new DistributedLock() {
            private final Map<String, String> tomados = new ConcurrentHashMap<>();
            private final Map<String, AtomicLong> fencing = new ConcurrentHashMap<>();

            @Override
            public Optional<LockHandle> tryAcquire(LockKey key, Duration ttl) {
                String token = UUID.randomUUID().toString();
                if (tomados.putIfAbsent(key.value(), token) != null) {
                    return Optional.empty();
                }
                long n = fencing.computeIfAbsent(key.value(), k -> new AtomicLong()).incrementAndGet();
                return Optional.of(new LockHandle(key, token, n, Instant.now().plus(ttl)));
            }

            @Override
            public boolean extend(LockHandle handle, Duration ttl) {
                return handle.token().equals(tomados.get(handle.key().value()));
            }

            @Override
            public void release(LockHandle handle) {
                tomados.remove(handle.key().value(), handle.token());
            }
        };
    }
}

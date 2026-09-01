package com.fintech.shared.lock.redisson;

import com.fintech.shared.lock.DistributedLock;
import com.fintech.shared.lock.LockAcquisitionException;
import com.fintech.shared.lock.LockHandle;
import com.fintech.shared.lock.LockKey;
import com.fintech.shared.lock.LockProperties;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Candado distribuido sobre Redisson.
 *
 * <p><b>Qué aporta sobre la implementación con {@code StringRedisTemplate}.</b> Redisson resuelve
 * de fábrica el escenario más peligroso de un candado con expiración: que el TTL venza <i>mientras
 * el trabajo sigue vivo</i>. Su <b>watchdog</b> renueva el arrendamiento cada tercio del tiempo
 * configurado mientras el hilo lo siga sosteniendo, y deja de renovarlo en cuanto el proceso muere
 * — que es exactamente el comportamiento que queremos: nadie se queda con el candado, y nadie lo
 * pierde por tardarse.
 *
 * <p><b>Lo que Redisson NO da, y aquí se añade.</b> {@link RLock} no expone un
 * <i>fencing token</i>. Sin él, un titular cuyo candado expiró puede seguir escribiendo creyéndose
 * dueño. Se resuelve con un contador atómico por llave: sólo el que adquiere lo incrementa, y la
 * escritura lleva el número para que un titular viejo sea rechazado.
 *
 * <p><b>Afinidad de hilo.</b> {@code RLock} es reentrante y está ligado al hilo que lo tomó: sólo
 * ese hilo puede liberarlo. Es una restricción real y no un detalle — el patrón de uso
 * ({@code withLock}, o tomar/procesar/soltar dentro del mismo worker) la respeta. Liberar desde
 * otro hilo obligaría a {@code forceUnlock()}, que reintroduce justo el problema que el token de
 * propiedad evita.
 */
public class RedissonDistributedLock implements DistributedLock {

    private static final Logger log = LoggerFactory.getLogger(RedissonDistributedLock.class);

    /**
     * Arrendamiento gestionado por el watchdog de Redisson. Con este valor no se fija un TTL fijo:
     * Redisson renueva mientras el hilo viva y suelta cuando muere.
     */
    public static final Duration WATCHDOG = Duration.ofMillis(-1);

    private final RedissonClient redisson;
    private final LockProperties properties;
    private final Clock clock;

    public RedissonDistributedLock(RedissonClient redisson, LockProperties properties) {
        this(redisson, properties, Clock.systemUTC());
    }

    public RedissonDistributedLock(RedissonClient redisson, LockProperties properties, Clock clock) {
        this.redisson   = redisson;
        this.properties = properties;
        this.clock      = clock;
    }

    /**
     * El sabor de candado, configurable por {@code fintech.lock.flavor}.
     *
     * <p>Con {@code waitTime = 0} —el modo de uso del reparto— los tres se comportan igual: se
     * intenta una vez y se devuelve. {@code SPIN} y {@code FAIR} sólo se diferencian cuando alguien
     * <b>espera</b>, y por eso el predeterminado es {@code STANDARD}. Queda configurable para poder
     * cambiarlo sin tocar código el día que se decida esperar.
     */
    private RLock lockFor(LockKey key) {
        return switch (properties.getFlavor()) {
            case SPIN     -> redisson.getSpinLock(key.value());
            case FAIR     -> redisson.getFairLock(key.value());
            case STANDARD -> redisson.getLock(key.value());
        };
    }

    @Override
    public Optional<LockHandle> tryAcquire(LockKey key, Duration ttl) {
        boolean watchdog = WATCHDOG.equals(ttl);
        if (!watchdog && (ttl == null || ttl.isZero() || ttl.isNegative())) {
            throw new IllegalArgumentException("El TTL del candado debe ser positivo: " + ttl);
        }
        try {
            RLock lock = lockFor(key);

            // `RLock` es REENTRANTE: el hilo que ya lo tiene vuelve a entrar y sólo sube el
            // contador de posesión. Aquí eso es un fallo, no una comodidad — la promesa es «la
            // primera petición se procesa y las siguientes se descartan», y con reentrancia una
            // segunda petición servida por el mismo hilo del pool pasaría de largo.
            //
            // Se comprueba ANTES de tomar, y no deshaciendo después con `unlock()`: al soltar una
            // re-entrada, Redisson **reestablece el arrendamiento a su valor por defecto** en vez
            // de al TTL que se pidió. Una llave de 800 ms se convertía así en una de 30 s, y el
            // pod que muere dejaba el candado retenido mucho más de lo pactado.
            if (lock.isHeldByCurrentThread()) {
                log.trace("Re-entrada rechazada key={} — este hilo ya lo posee", key);
                return Optional.empty();
            }
            // waitTime = 0 (predeterminado): no se espera. "Ocupado" es un resultado normal del
            // reparto, no un error; el worker pasa a la siguiente unidad en vez de bloquear un hilo.
            // Con waitTime = 0, Redisson ni siquiera llega a suscribirse al canal pub/sub.
            long waitMs = properties.getWaitTime().toMillis();
            boolean acquired = watchdog
                    ? lock.tryLock(waitMs, TimeUnit.MILLISECONDS)
                    : lock.tryLock(waitMs, ttl.toMillis(), TimeUnit.MILLISECONDS);
            if (!acquired) {
                log.trace("Candado ocupado key={}", key);
                return Optional.empty();
            }

            long fencing = redisson.getAtomicLong(key.fenceKey()).incrementAndGet();
            Instant expiresAt = watchdog
                    ? Instant.now(clock).plusSeconds(30)   // referencia; el watchdog la extiende
                    : Instant.now(clock).plus(ttl);

            log.debug("Candado tomado key={} fencing={} watchdog={}", key, fencing, watchdog);
            // El token del handle es informativo aquí: la propiedad la lleva Redisson por hilo.
            return Optional.of(new LockHandle(key, Thread.currentThread().getName(), fencing, expiresAt));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new LockAcquisitionException(key, ex);
        } catch (RuntimeException ex) {
            // Redis no disponible. No se degrada a "seguir sin candado": en un proceso financiero
            // correr sin exclusión es peor que no correr.
            throw new LockAcquisitionException(key, ex);
        }
    }

    @Override
    public boolean extend(LockHandle handle, Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("El TTL del candado debe ser positivo: " + ttl);
        }
        RLock lock = lockFor(handle.key());
        if (!lock.isHeldByCurrentThread()) {
            log.warn("No se puede renovar key={} — este hilo ya no es el titular", handle.key());
            return false;
        }
        // `RLock` no expone renovación: no extiende `RExpirable` (verificado sobre 3.40.2, su
        // superficie es tryLock/unlock/forceUnlock/isHeld/remainTimeToLive). Se renueva por la
        // llave, que es API pública y hace exactamente lo mismo.
        //
        // Con `WATCHDOG` esto sobra: Redisson renueva solo cada tercio del arrendamiento mientras
        // el hilo viva, que es la forma correcta de cubrir trabajo de duración desconocida.
        boolean renovado = redisson.getKeys().expire(handle.key().value(), ttl.toMillis(), TimeUnit.MILLISECONDS);
        if (!renovado) {
            log.warn("Renovación sin efecto key={} — la llave ya no existe", handle.key());
        }
        return renovado;
    }

    @Override
    public void release(LockHandle handle) {
        try {
            RLock lock = lockFor(handle.key());
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            } else {
                // El arrendamiento venció con el trabajo todavía vivo. Es el escenario para el que
                // existe el fencing token: lo escrito por este titular ya no es de fiar.
                log.warn("Liberación sin efecto key={} — el candado expiró o cambió de dueño", handle.key());
            }
        } catch (RuntimeException ex) {
            // Liberar nunca debe tumbar al llamador: el arrendamiento acabará venciendo igual.
            log.error("Fallo al liberar el candado key={}: {}", handle.key(), ex.getMessage());
        }
    }
}

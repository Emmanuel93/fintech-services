package com.fintech.shared.lock;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Configuración del candado distribuido.
 *
 * <p>Todo es ajustable por propiedad porque la elección correcta depende del perfil de uso, y ese
 * perfil no se conoce hasta medirlo en volumen real.
 */
@ConfigurationProperties(prefix = "fintech.lock")
public class LockProperties {

    public enum Provider {
        /** Redisson: arrendamiento con watchdog y reentrancia. El predeterminado. */
        REDISSON,
        /** Spring Data Redis puro: SET NX PX + WATCH/MULTI/EXEC. Sin dependencias nuevas. */
        REDIS_TEMPLATE
    }

    public enum Flavor {
        /**
         * {@code getLock()}. Con {@code waitTime = 0} —que es como se usa aquí— Redisson devuelve
         * de inmediato y <b>no llega a suscribirse al canal pub/sub</b>: la suscripción sólo ocurre
         * cuando alguien espera. Para "el primero entra y los demás siguen de largo", es el correcto.
         */
        STANDARD,
        /**
         * {@code getSpinLock()}. Sustituye la notificación por pub/sub por sondeo con backoff.
         * <b>Sólo aporta si se decide esperar</b> ({@code waitTime > 0}) sobre muchísimas llaves
         * distintas, donde abrir una suscripción por llave sí pesa.
         */
        SPIN,
        /**
         * {@code getFairLock()}. Cola FIFO entre los que esperan. Irrelevante con
         * {@code waitTime = 0}, y más caro: mantiene la cola en Redis.
         */
        FAIR
    }

    private boolean enabled = true;
    private Provider provider = Provider.REDISSON;
    private Flavor flavor = Flavor.STANDARD;

    /**
     * Cuánto dura la llave si el llamador no fija un TTL. Es la ventana en la que una petición
     * repetida se descarta por ser la misma.
     */
    private Duration defaultTtl = Duration.ofSeconds(30);

    /**
     * Cuánto espera un llamador por un candado ocupado. <b>Cero</b> es deliberado: en el reparto,
     * el que no gana pasa a la siguiente unidad en vez de bloquear un hilo esperando.
     */
    private Duration waitTime = Duration.ZERO;

    public boolean isEnabled()          { return enabled; }
    public void setEnabled(boolean e)   { this.enabled = e; }
    public Provider getProvider()       { return provider; }
    public void setProvider(Provider p) { this.provider = p; }
    public Flavor getFlavor()           { return flavor; }
    public void setFlavor(Flavor f)     { this.flavor = f; }
    public Duration getDefaultTtl()     { return defaultTtl; }
    public void setDefaultTtl(Duration t) { this.defaultTtl = t; }
    public Duration getWaitTime()       { return waitTime; }
    public void setWaitTime(Duration w) { this.waitTime = w; }
}

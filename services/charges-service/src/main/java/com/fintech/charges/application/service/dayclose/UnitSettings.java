package com.fintech.charges.application.service.dayclose;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Los tiempos y límites del cierre por cuenta; ver {@code fintech.day-close} en application.yml. */
@Component
public class UnitSettings {

    private final Duration lease;
    private final int maxAttempts;
    private final Duration retryBackoff;
    private final Duration redispatchAfter;
    private final int dispatchPage;
    private final double maxQuarantinedRatio;
    private final int minQuarantinedAllowed;

    public UnitSettings(@Value("${fintech.day-close.units.lease:PT2M}") Duration lease,
                        @Value("${fintech.day-close.units.max-attempts:3}") int maxAttempts,
                        @Value("${fintech.day-close.units.retry-backoff:PT1M}") Duration retryBackoff,
                        @Value("${fintech.day-close.units.redispatch-after:PT2M}") Duration redispatchAfter,
                        @Value("${fintech.day-close.units.dispatch-page:1000}") int dispatchPage,
                        @Value("${fintech.day-close.units.max-quarantined-ratio:0.01}") double maxQuarantinedRatio,
                        @Value("${fintech.day-close.units.min-quarantined-allowed:10}") int minQuarantinedAllowed) {
        this.lease                 = lease;
        this.maxAttempts           = maxAttempts;
        this.retryBackoff          = retryBackoff;
        this.redispatchAfter       = redispatchAfter;
        this.dispatchPage          = dispatchPage;
        this.maxQuarantinedRatio   = maxQuarantinedRatio;
        this.minQuarantinedAllowed = minQuarantinedAllowed;
    }

    /** Cuánto tiene un pod una cuenta antes de que el monitor la dé por perdida. */
    public Duration lease()             { return lease; }
    /** Intentos antes de la cuarentena. */
    public int maxAttempts()            { return maxAttempts; }
    public Duration retryBackoff()      { return retryBackoff; }
    public Duration redispatchAfter()   { return redispatchAfter; }
    public int dispatchPage()           { return dispatchPage; }

    /**
     * Cuántas cuentas en cuarentena admite un día antes de tratarlo como falla general: el mayor entre
     * {@code min-quarantined-allowed} y la proporción de las cuentas del día. Más que eso no es una cuenta
     * con un dato malo: es un error que tocó a todas, y el día no cierra.
     */
    public int quarantinedAllowed(int accounts) {
        return Math.max(minQuarantinedAllowed, (int) Math.floor(accounts * maxQuarantinedRatio));
    }
}

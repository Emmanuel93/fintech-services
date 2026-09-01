package com.fintech.closing.domain;

import java.util.List;

/**
 * Las fases de un cierre, y el orden en que dependen unas de otras.
 *
 * <p>El orden <b>no</b> se expresa como horas de reloj —que es como está hoy: 23:00, 23:30, 23:59,
 * 01:00— sino como dependencia entre fases. Una fase no arranca hasta que la anterior selló sobre
 * la misma fecha de negocio. Si el devengo se alarga, la mora <b>espera</b> en vez de leer datos
 * viejos; hoy nada lo detecta.
 */
public enum ClosePhase {

    /** Huecos, deriva y realineación. Va primero: devengar sobre datos incompletos cuesta un reproceso contable. */
    RECONCILE(null),
    ACCRUAL(RECONCILE),
    DELINQUENCY(ACCRUAL),
    RISK(DELINQUENCY),
    CUTOFF(DELINQUENCY),
    POSTING_DRAIN(CUTOFF),
    SEAL(POSTING_DRAIN),
    /** Empuja el corte a cartera. Va después del sello: no se propaga un número que aún puede cambiar. */
    PROPAGATE(SEAL),
    BILLING(SEAL),
    COMMISSION(BILLING),
    PERIOD_CLOSE(COMMISSION);

    private final ClosePhase requires;

    ClosePhase(ClosePhase requires) { this.requires = requires; }

    /** La fase que tiene que haber sellado antes de que ésta arranque. */
    public ClosePhase requires() { return requires; }

    public boolean isFirst() { return requires == null; }

    /** Las fases diarias, en orden. Las mensuales cuelgan del sello del último día del período. */
    public static List<ClosePhase> dailyOrder() {
        return List.of(RECONCILE, ACCRUAL, DELINQUENCY, RISK, CUTOFF, POSTING_DRAIN, SEAL, PROPAGATE);
    }

    public static List<ClosePhase> monthlyOrder() {
        return List.of(BILLING, COMMISSION, PERIOD_CLOSE);
    }
}

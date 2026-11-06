package com.fintech.closing.domain;

/**
 * De dónde sale la fecha de corte de una cuenta.
 *
 * <p><b>El cierre deriva y persiste su propio calendario; no consulta el de cartera.</b> Para un
 * producto no revolvente la cadencia coincide con el vencimiento de la cuota, así que la tentación
 * es leer {@code installments.due_date}. No se hace: leer cartera en línea durante la ventana rompe
 * el aislamiento, el corte es una decisión de política que un plan de pagos no puede expresar
 * —cortar N días antes, correrse si cae inhábil— y un corte sellado es inmutable, así que una
 * reestructura no puede reescribirlo hacia atrás.
 */
public enum CutoffRule {
    /** El producto no tiene corte. */
    NONE,
    /** No revolvente: la cadencia del plan (semanal, quincenal, mensual) desde la activación. */
    INSTALLMENT_DUE_DATE,
    /** Revolvente: ciclo mensual anclado al día de activación del crédito. */
    CYCLE_FROM_ACTIVATION,
    /** A fixed weekday (1 = Monday … 7 = Sunday), every week or every other week by the payment frequency. */
    DAY_OF_WEEK,
    /** Día fijo del mes, igual para todas las cuentas del producto. */
    DAY_OF_MONTH,
    /**
     * The fortnight: two fixed days of every month, the same for every account of the product (the distributor line:
     * the 6th and the 21st; without days, the 15th and the last day). The cutoff is the end of the fortnight and does
     * not move on a non-business day (the nightly close runs every day); the payment due date, a fixed day (the 18th
     * and the 3rd) or a number of days after the cutoff, does. See {@link SemiMonthlyCalendar}.
     */
    SEMI_MONTHLY
}

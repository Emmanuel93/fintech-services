package com.fintech.scoring.application;

/**
 * Por qué una evaluación no se pudo hacer todavía — y si tiene sentido volver a intentarlo.
 *
 * <p>Antes las dos razones salían por el mismo {@code Optional.empty()} y el mismo log: «no hay
 * reporte de buró» y «no hay política activa para este producto». Son problemas distintos, con
 * dueños distintos y con respuestas opuestas — a uno se le espera, al otro no se le espera nunca
 * porque falta una fila de configuración que no va a aparecer sola.
 *
 * <p>{@link #isTransient()} es lo que decide si el reintento sirve. Reintentar lo que no va a
 * cambiar sólo retrasa el momento en que alguien se entera.
 */
public class EvaluationNotReady extends RuntimeException {

    public enum Reason {
        /**
         * El prefetch del buró va en camino y aún no aterriza. Es una <b>carrera</b>: el evento de
         * puntaje llegó antes que el reporte, y esperar un poco lo resuelve.
         */
        BUREAU_REPORT_PENDING(true),

        /**
         * El prefetch del buró terminó en {@code FAILED}. Reintentar la evaluación no sirve de
         * nada: lo que hay que reintentar es <b>la consulta al buró</b>, y eso no ocurre aquí.
         *
         * <p>Y no hay salida humana: nadie puede inventar un puntaje de buró. La solicitud queda
         * esperando a que alguien vuelva a pedir el reporte o la rechace a mano.
         */
        PREFETCH_FAILED(false),

        /** Nunca hubo prefetch para este prospecto — no hubo consentimiento, o no se disparó. */
        NO_PREFETCH(false),

        /** Falta la política de scoring del producto. Es configuración, no tiempo. */
        NO_ACTIVE_POLICY(false);

        private final boolean isTransient;

        Reason(boolean isTransient) { this.isTransient = isTransient; }

        /** Si esperar puede resolverlo. Lo demás exige que alguien haga algo. */
        public boolean isTransient() { return isTransient; }
    }

    private final Reason reason;

    public EvaluationNotReady(Reason reason, String detail) {
        super(detail);
        this.reason = reason;
    }

    public Reason reason()       { return reason; }
    public boolean isTransient() { return reason.isTransient(); }
}

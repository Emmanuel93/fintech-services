package com.fintech.scoring.application;

/**
 * El reporte de buró va en camino: es una <b>carrera</b>, y esperar la resuelve.
 *
 * <p>Es un tipo aparte y no una bandera dentro de {@link EvaluationNotReady} porque la
 * reintentabilidad se decide mejor en el sistema de tipos que en una expresión: un
 * {@code @Retryable(retryFor = BureauReportPending.class)} dice lo mismo sin depender del nombre de un
 * bean ni de una cadena que nadie compila.
 *
 * <p>Todo lo demás —prefetch fallido, sin prefetch, sin política— es {@link EvaluationNotReady} a
 * secas y <b>no se reintenta</b>: esperar no resucita un prefetch muerto ni hace aparecer una fila
 * de configuración, y reintentarlo sólo retrasa el momento en que alguien se entera.
 */
public class BureauReportPending extends EvaluationNotReady {

    public BureauReportPending(String detail) {
        super(Reason.BUREAU_REPORT_PENDING, detail);
    }
}

package com.fintech.scoring.application;

/**
 * El reporte de buró va en camino: es una <b>carrera</b>, y esperar la resuelve.
 *
 * <p>Es un tipo aparte y no una bandera dentro de {@link EvaluationNotReady} porque la
 * reintentabilidad se decide mejor en el sistema de tipos que en una expresión: un
 * {@code @Retryable(retryFor = BureauReportPending.class)} dice lo mismo sin depender del nombre de un
 * bean ni de una cadena que nadie compila.
 *
 * <p>Dos carreras viajan con este tipo: el prefetch en vuelo ({@code BUREAU_REPORT_PENDING}) y el que
 * todavía no se ve ({@code NO_PREFETCH}): el alta y la solicitud llegan por tópicos distintos, y la
 * solicitud puede adelantarse al prefetch. Se observó en vivo tras un reinicio: el alta tardó 3 s en
 * procesarse y la solicitud, que llegó antes, se daba por perdida. Agotados los reintentos, el motivo
 * se conserva para quien intervenga.
 *
 * <p>Lo demás —prefetch fallido, sin política— es {@link EvaluationNotReady} a secas y <b>no se
 * reintenta</b>: esperar no resucita un prefetch muerto ni hace aparecer una fila de configuración.
 */
public class BureauReportPending extends EvaluationNotReady {

    public BureauReportPending(String detail) {
        super(Reason.BUREAU_REPORT_PENDING, detail);
    }

    /** Una carrera con otro motivo (hoy, {@code NO_PREFETCH}); el motivo debe ser transitorio. */
    public BureauReportPending(Reason reason, String detail) {
        super(reason, detail);
        if (!reason.isTransient()) {
            throw new IllegalArgumentException(reason + " no es transitorio: no puede viajar como carrera");
        }
    }
}

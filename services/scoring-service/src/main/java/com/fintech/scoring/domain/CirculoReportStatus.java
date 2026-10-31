package com.fintech.scoring.domain;

public enum CirculoReportStatus {
    PENDING, SUCCESS, NOT_FOUND, NO_HIT, ERROR;

    /** Sin historial: el buró contestó y no tiene nada de la persona. Es un resultado, no una falla. */
    public boolean isNoHistory() {
        return this == NO_HIT || this == NOT_FOUND;
    }

    /** Con lo que se puede decidir: un reporte con datos, o la constancia de que no hay historial. */
    public boolean isUsable() {
        return this == SUCCESS || isNoHistory();
    }
}

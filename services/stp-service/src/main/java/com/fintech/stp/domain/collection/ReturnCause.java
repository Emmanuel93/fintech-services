package com.fintech.stp.domain.collection;

/**
 * Why a credit is returned to the payer, with the code of Banxico's SPEI return-cause catalog. Answering "devolver"
 * to STP makes it send the money back at once; it never reaches the portfolio.
 */
public enum ReturnCause {
    CUENTA_INEXISTENTE(1, "Cuenta inexistente"),
    CUENTA_BLOQUEADA(2, "Cuenta bloqueada"),
    CUENTA_CANCELADA(3, "Cuenta cancelada"),
    FALTA_INFORMACION(14, "Falta información mandatoria para completar el pago"),
    TIPO_DE_PAGO_ERRONEO(15, "Tipo de pago erróneo");

    private final int code;
    private final String description;

    ReturnCause(int code, String description) {
        this.code = code;
        this.description = description;
    }

    public int code() {
        return code;
    }

    public String description() {
        return description;
    }
}

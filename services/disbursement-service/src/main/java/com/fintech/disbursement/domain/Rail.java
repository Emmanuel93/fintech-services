package com.fintech.disbursement.domain;

/** Vía por la que sale el dinero. */
public enum Rail {
    /** Transferencia interbancaria mexicana. */
    SPEI,
    /** Cobro digital de Banxico. */
    CODI,
    /** Movimiento dentro de la propia plataforma: no sale dinero al sistema bancario. */
    INTERNAL
}

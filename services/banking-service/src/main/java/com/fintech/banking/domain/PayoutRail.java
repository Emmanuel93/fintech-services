package com.fintech.banking.domain;

/** Vía por la que sale el dinero. Mismo vocabulario que {@code disbursement.Rail}, sin compartir clase. */
public enum PayoutRail {
    /** Transferencia interbancaria mexicana. */
    SPEI,
    /** Cobro digital de Banxico. */
    CODI,
    /** Movimiento dentro de la propia plataforma: no sale dinero al sistema bancario. */
    INTERNAL
}

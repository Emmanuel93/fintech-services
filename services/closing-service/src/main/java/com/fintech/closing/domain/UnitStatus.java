package com.fintech.closing.domain;

public enum UnitStatus {
    PENDING,
    /** Tomada por un pod, con arrendamiento vivo. */
    CLAIMED,
    DONE,
    FAILED,
    /** La política del producto la excluye, o la cuenta ya no aplica. No es un error. */
    SKIPPED,
    /**
     * Cargos dejó la cuenta fuera del cierre del día (cuarentena o retenida): su corte espera a que su interés
     * llegue ({@code credit-portfolio.account-day-closed}). No cuenta como en vuelo: el corte del resto termina.
     */
    HELD
}

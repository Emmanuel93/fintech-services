package com.fintech.banking.domain;

/**
 * El estado operativo de una cuenta propia.
 *
 * <p>{@code SUSPENDED} existe para poder sacar una cuenta del ruteo <b>sin borrar su historia</b>:
 * una cuenta con la que ya se pagó tiene movimientos que conciliar durante meses. Cerrarla o
 * eliminarla para que deje de rutear rompería la conciliación de lo que ya salió por ella.
 */
public enum BankAccountStatus {
    ACTIVE, SUSPENDED, CLOSED;

    /** Sólo una cuenta ACTIVE puede ser elegida para que salga un pago por ella. */
    public boolean puedeOperar() { return this == ACTIVE; }
}

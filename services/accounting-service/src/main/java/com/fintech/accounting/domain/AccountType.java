package com.fintech.accounting.domain;

/**
 * La naturaleza de la cuenta, que decide cómo se presenta su saldo y si entra al balance.
 *
 * <p>{@code CONTRA_ASSET} existe porque la estimación preventiva (1290) es un activo de naturaleza
 * acreedora: con {@code saldo = cargos − abonos} sale en negativo, que es correcto y ilegible.
 *
 * <p>{@code ORDER} son las cuentas de orden —líneas autorizadas—: cuadran entre sí y <b>no forman
 * parte del balance patrimonial</b>. Sumarlas a la balanza infla el activo con dinero que nunca
 * salió y el total deja de ser el total de nada.
 */
public enum AccountType {
    ASSET, CONTRA_ASSET, LIABILITY, INCOME, EXPENSE, EQUITY, ORDER;

    /** Naturaleza acreedora: su saldo se presenta con el signo invertido. */
    public boolean isCreditNature() {
        return this == LIABILITY || this == INCOME || this == EQUITY || this == CONTRA_ASSET;
    }

    public boolean isOrder() {
        return this == ORDER;
    }
}

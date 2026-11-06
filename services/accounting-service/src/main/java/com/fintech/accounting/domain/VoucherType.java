package com.fintech.accounting.domain;

import java.util.Collection;

/**
 * El tipo de póliza de la práctica contable mexicana.
 *
 * <p>No es una etiqueta: junto con la sucursal y el período forma la llave del folio consecutivo,
 * que es lo que un auditor sigue. Ingreso es lo que entra a caja, egreso lo que sale, y diario todo
 * lo demás — que es la mayoría, porque el devengo no mueve efectivo.
 */
public enum VoucherType {
    DIARIO, INGRESO, EGRESO;

    /**
     * The type from the money itself: a voucher that debits Bancos (1101) is an income, one that credits it an
     * expense, and any other a journal voucher. With transit accounts a payment applied on the portfolio's date no
     * longer moves cash (it waits in 1103 for the bank), so the event's name no longer says it; its accounts do.
     */
    public static VoucherType ofCash(Collection<String> debitAccounts, Collection<String> creditAccounts) {
        if (debitAccounts.contains(AccountCodes.BANCOS)) return INGRESO;
        if (creditAccounts.contains(AccountCodes.BANCOS)) return EGRESO;
        return DIARIO;
    }
}

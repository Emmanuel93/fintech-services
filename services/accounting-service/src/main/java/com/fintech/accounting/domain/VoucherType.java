package com.fintech.accounting.domain;

/**
 * El tipo de póliza de la práctica contable mexicana.
 *
 * <p>No es una etiqueta: junto con la sucursal y el período forma la llave del folio consecutivo,
 * que es lo que un auditor sigue. Ingreso es lo que entra a caja, egreso lo que sale, y diario todo
 * lo demás — que es la mayoría, porque el devengo no mueve efectivo.
 */
public enum VoucherType {
    DIARIO, INGRESO, EGRESO;

    /** Deduce el tipo del hecho económico. Un hecho sin efectivo es siempre de diario. */
    public static VoucherType of(String triggerEvent) {
        if (triggerEvent == null) return DIARIO;
        return switch (triggerEvent) {
            case "PAYMENT_APPLIED", "RECOVERY_PAYMENT" -> INGRESO;
            case "PAYMENT_RETURNED", "WALLET_WITHDRAWAL" -> EGRESO;
            default -> triggerEvent.startsWith("DISPOSITION") ? EGRESO : DIARIO;
        };
    }
}

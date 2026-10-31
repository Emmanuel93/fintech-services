package com.fintech.scoring.domain;

/**
 * Lo que una política puede pedirle a quien no tiene historial en buró.
 *
 * <p>Sin historial no hay con qué puntuar el comportamiento de pago, y hay productos —el de
 * estudiantes es el ejemplo— cuyo público casi nunca lo tiene. La política del producto puede pedir
 * un dato más en lugar de decidir a ciegas. La lista es cerrada a propósito: lo que se pide lo revisa
 * una persona y lo nombra la app, y un texto libre por producto no lo entendería ninguna de las dos.
 */
public enum NoHistoryRequirement {

    PROOF_OF_ENROLLMENT("Constancia de estudios vigente"),
    INCOME_PROOF("Comprobante de ingresos"),
    ADDRESS_PROOF("Comprobante de domicilio"),
    BANK_STATEMENT_3M("Estados de cuenta de los últimos 3 meses"),
    GUARANTOR("Aval u obligado solidario"),
    PERSONAL_REFERENCES("Referencias personales");

    private final String label;

    NoHistoryRequirement(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}

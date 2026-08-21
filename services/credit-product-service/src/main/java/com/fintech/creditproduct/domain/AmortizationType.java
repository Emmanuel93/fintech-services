package com.fintech.creditproduct.domain;

/**
 * Método de amortización. Aplica únicamente a productos INSTALLMENT.
 * La medición contable posterior usa costo amortizado con TIE/EIR (IFRS 9 §5.4).
 *
 * FRENCH — cuotas iguales; capital crece, interés decrece. Estándar consumo (CNBV).
 * GERMAN — capital fijo; cuotas decrecientes. Usado en créditos corporativos/grupales.
 * BULLET — interés periódico + capital íntegro al vencimiento. Para líneas puente/distribuidoras.
 */
public enum AmortizationType {
    FRENCH,
    GERMAN,
    BULLET
}

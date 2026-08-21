package com.fintech.creditproduct.domain;

/**
 * Estructura de crédito: define cómo fluye el dinero y cómo se repaga.
 *
 * REVOLVING  — línea abierta: dispones, pagas, vuelves a disponer. Sin tabla de amortización fija.
 * INSTALLMENT — disposición única, calendario fijo de cuotas hasta saldo cero (costo amortizado IFRS 9).
 */
public enum ProductBehavior {
    REVOLVING,
    INSTALLMENT
}

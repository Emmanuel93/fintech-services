package com.fintech.creditportfolio.domain;

/**
 * Si esta disposición tiene calendario propio o se exige entera en el corte.
 *
 * <p>Las dos mecánicas del negocio, que el modelo confundía en una:
 *
 * <ul>
 *   <li>{@code AMORTIZED} — el <b>distribuidor</b> decide al colocar «a cuántos meses se lo dejas».
 *       El calendario nace con la disposición.</li>
 *   <li>{@code REVOLVING} — una compra con <b>tarjeta</b>. Nace sin plan: es exigible entera en la
 *       siguiente fecha de pago posterior al corte, salvo que el titular la difiera antes.</li>
 * </ul>
 *
 * <p>Hasta BK-24 todo era {@code AMORTIZED}, así que una compra con tarjeta nacía ya parcializada al
 * plazo por defecto del producto — ni lo que el cliente pidió ni lo que el corte debía exigirle.
 */
public enum DispositionPlanMode {
    REVOLVING, AMORTIZED;

    public boolean tienePlan() { return this == AMORTIZED; }
}

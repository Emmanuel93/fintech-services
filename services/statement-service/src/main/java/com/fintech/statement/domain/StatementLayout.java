package com.fintech.statement.domain;

/**
 * La forma de los datos del documento, que decide qué ensamblador lo llena. La plantilla es por
 * producto ({@link StatementTemplate}); la forma, por familia:
 *
 * <ul>
 *   <li>{@link #INSTALLMENT} — el detalle es el plan de pagos: qué cuota toca, cuántas van, qué falta.</li>
 *   <li>{@link #REVOLVING} — el detalle son los movimientos: disposiciones, pagos, cargos; con pago
 *       mínimo y pago para no generar intereses.</li>
 *   <li>{@link #DISTRIBUTOR} — el detalle son las colocaciones por cliente: cuánto le toca enterar a la
 *       distribuidora en el período por cada una.</li>
 * </ul>
 */
public enum StatementLayout {

    INSTALLMENT, REVOLVING, DISTRIBUTOR;

    /**
     * La forma de un producto que todavía no tiene plantilla: con ella se registra su documento, que
     * queda fallido hasta que la plantilla exista.
     */
    public static StatementLayout defaultFor(String holderKind, String productBehavior) {
        if ("DISTRIBUTOR".equalsIgnoreCase(holderKind)) return DISTRIBUTOR;
        return "REVOLVING".equalsIgnoreCase(productBehavior) ? REVOLVING : INSTALLMENT;
    }

    /** La forma con que se registra el documento de un producto. */
    public static StatementLayout of(String productType, String holderKind, String productBehavior) {
        return StatementTemplate.forProduct(productType).map(StatementTemplate::layout)
                .orElseGet(() -> defaultFor(holderKind, productBehavior));
    }
}

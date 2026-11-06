package com.fintech.statement.domain;

import java.util.Arrays;
import java.util.Optional;

/**
 * Una plantilla por producto. Cada una tiene su título, su color, su tabla de detalle y su recuadro
 * de «lo que debes saber», y se edita por separado en Jaspersoft Studio: cambiar el estado de cuenta
 * de la tarjeta no toca el del préstamo de nómina.
 *
 * <p>Lo que comparten los productos de la misma familia es la <b>forma de los datos</b>
 * ({@link StatementLayout}): un préstamo de nómina y uno personal llenan los mismos campos —el plan de
 * pagos— aunque cada uno los pinte a su manera.
 *
 * <p>Un producto sin plantilla no recibe una genérica: su documento queda {@code FAILED} con la causa
 * y se genera en cuanto se despliegue la suya.
 */
public enum StatementTemplate {

    PERSONAL_LOAN("estado-cuenta-prestamo-personal", StatementLayout.INSTALLMENT),
    PAYROLL_LOAN("estado-cuenta-prestamo-nomina", StatementLayout.INSTALLMENT),
    GROUP_LOAN("estado-cuenta-prestamo-grupal", StatementLayout.INSTALLMENT),
    MICRO_LOAN("estado-cuenta-microcredito", StatementLayout.INSTALLMENT),
    SME_LOAN("estado-cuenta-credito-pyme", StatementLayout.INSTALLMENT),
    CREDIT_CARD("estado-cuenta-tarjeta-credito", StatementLayout.REVOLVING),
    REVOLVING_LINE("estado-cuenta-linea-revolvente", StatementLayout.REVOLVING),
    BUSINESS_REVOLVING_LINE("estado-cuenta-linea-empresarial", StatementLayout.REVOLVING),
    DISTRIBUTOR_LINE("estado-cuenta-linea-distribuidora", StatementLayout.DISTRIBUTOR);

    private final String file;
    private final StatementLayout layout;

    StatementTemplate(String file, StatementLayout layout) {
        this.file   = file;
        this.layout = layout;
    }

    /** El nombre del JRXML en {@code classpath:reports/}. */
    public String file() { return file; }

    /** Qué ensamblador la llena. */
    public StatementLayout layout() { return layout; }

    /** La plantilla del producto ({@code PERSONAL_LOAN}, {@code CREDIT_CARD}, …), si tiene. */
    public static Optional<StatementTemplate> forProduct(String productType) {
        if (productType == null) return Optional.empty();
        return Arrays.stream(values()).filter(t -> t.name().equalsIgnoreCase(productType)).findFirst();
    }
}

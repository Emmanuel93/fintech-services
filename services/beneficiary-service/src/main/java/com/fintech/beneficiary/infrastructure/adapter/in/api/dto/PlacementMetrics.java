package com.fintech.beneficiary.infrastructure.adapter.in.api.dto;

import java.math.BigDecimal;

/**
 * Lo que de una colocación no sabe la colocación.
 *
 * <p>La bonificación la lleva commission-service; los pagos y la mora, credit-portfolio, en el
 * calendario que cuelga de la disposición ({@code scheduleId = dispositionId}). Se agrupan aquí en
 * vez de dispersarse como parámetros sueltos para que quede claro de un vistazo qué parte de la
 * respuesta es composición y qué parte es agregado.
 *
 * <p>{@code daysPastDue} lo decide el backend —días de gracia, festivos, fecha de aplicación del
 * pago— y nunca el reloj del teléfono.
 */
public record PlacementMetrics(
        BigDecimal commissionAccrued,
        int paymentsMade,
        int daysPastDue) {

    private static final PlacementMetrics NONE = new PlacementMetrics(BigDecimal.ZERO, 0, 0);

    /** Una colocación que todavía no cobra nada. */
    public static PlacementMetrics none() {
        return NONE;
    }
}

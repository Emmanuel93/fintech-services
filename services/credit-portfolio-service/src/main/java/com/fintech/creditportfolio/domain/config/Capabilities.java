package com.fintech.creditportfolio.domain.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Capability matrix — mirror of credit-product's Capabilities, received via the
 * {@code product-catalog.product-activated} event. Governs how the portfolio motor
 * behaves for accounts originated under a given product config version.
 *
 * <p>This is a projection (local read-model), not a shared model — it only carries
 * the fields the portfolio engine needs to drive behaviour.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Capabilities(
        boolean hasAmortizationSchedule,
        boolean hasCreditLimit,
        boolean allowsMultipleDispositions,
        String dispositionType,            // SELF_USE | THIRD_PARTY_CREDIT | PAYROLL
        boolean hasCutoffDate,
        boolean hasMinimumPayment,
        boolean allowsMultipleObligors,
        boolean commissionsEnabled,
        boolean requiresBeneficiaryPartyId,

        /**
         * Parcialidades, BNPL, salto de pago y elegibilidad para apoyos.
         *
         * <p>Puede llegar nula desde una versión de configuración anterior a BK-23: el catálogo se
         * guarda como JSONB y las filas viejas no la traen. Se lee siempre con
         * {@code OpcionesDePago.oNinguna(...)}, que devuelve todo apagado.
         */
        OpcionesDePago opcionesDePago
) {
    /**
     * Conservative defaults derived from product behavior, used only as a degraded
     * fallback when the authoritative config version has not yet propagated.
     */
    public static Capabilities degradedFor(String behavior, String dispositionType) {
        boolean revolving = "REVOLVING".equalsIgnoreCase(behavior);
        return new Capabilities(
                !revolving,                 // installment → has schedule
                revolving,                  // revolving → has credit limit
                revolving,                  // revolving → multiple dispositions
                dispositionType != null ? dispositionType : "SELF_USE",
                revolving,                  // revolving → cutoff date
                revolving,                  // revolving → minimum payment
                false, false, false);
    }

    /**
     * Compatibilidad con la configuración anterior a BK-23.
     *
     * <p>Existe para que las decenas de sitios que construyen {@code Capabilities} con nueve
     * argumentos —seeds, pruebas, fallbacks degradados— sigan compilando sin editarse. Quien no
     * declara opciones de pago no las tiene, que es lo correcto: un default permisivo convertiría
     * cada producto viejo del catálogo en uno que admite diferir y saltar pagos sin que nadie lo
     * haya decidido.
     */
    public Capabilities(boolean hasAmortizationSchedule, boolean hasCreditLimit,
                        boolean allowsMultipleDispositions, String dispositionType,
                        boolean hasCutoffDate, boolean hasMinimumPayment,
                        boolean allowsMultipleObligors, boolean commissionsEnabled,
                        boolean requiresBeneficiaryPartyId) {
        this(hasAmortizationSchedule, hasCreditLimit, allowsMultipleDispositions, dispositionType,
             hasCutoffDate, hasMinimumPayment, allowsMultipleObligors, commissionsEnabled,
             requiresBeneficiaryPartyId, OpcionesDePago.ninguna());
    }

    /** Nunca nulo, venga de donde venga la fila. */
    public OpcionesDePago opciones() {
        return OpcionesDePago.oNinguna(opcionesDePago);
    }
}

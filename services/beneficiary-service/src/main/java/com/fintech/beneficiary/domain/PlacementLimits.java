package com.fintech.beneficiary.domain;

import java.math.BigDecimal;

/**
 * Los límites de una colocación, tal como los configura el producto.
 *
 * <p>Vienen de `credit-product` (`min_amount`, `max_amount`, `amount_step`, `min_term`,
 * `max_term`, `term_step` de `DISTRIBUTOR_LINE`) y se cambian sin redeploy, igual que la tasa.
 *
 * <p><b>El tope por beneficiario no es el tamaño de la línea.</b> La línea acota cuánto puede
 * deber el distribuidor en total; esto acota cuánto puede darle <i>a una sola persona</i>. Sin ese
 * segundo límite, un distribuidor con línea de $500,000 podría colocárselos completos a un solo
 * cliente y dejar toda su línea colgada del historial de un desconocido. El tope por persona es
 * lo que obliga a diversificar, y por eso es una regla del producto y no una preferencia del
 * distribuidor.
 *
 * <p>El {@code amountStep} y el {@code termStep} no son cosmética del slider: si el servidor
 * aceptara $18,500 donde el producto sólo ofrece múltiplos de mil, la app y el backend estarían
 * cotizando productos distintos.
 */
public record PlacementLimits(
        BigDecimal minAmount,
        BigDecimal maxAmount,
        int amountStep,
        int minTerm,
        int maxTerm,
        int termStep) {

    public PlacementLimits {
        if (minAmount == null || maxAmount == null) {
            throw new PlacementValidationException(
                    "El producto no tiene configurado el rango de monto por colocación");
        }
        if (minAmount.compareTo(maxAmount) > 0) {
            throw new PlacementValidationException(
                    "Rango de monto inválido: mínimo " + minAmount + " sobre máximo " + maxAmount);
        }
        if (minTerm > maxTerm) {
            throw new PlacementValidationException(
                    "Rango de plazo inválido: mínimo " + minTerm + " sobre máximo " + maxTerm);
        }
        if (amountStep < 1 || termStep < 1) {
            throw new PlacementValidationException("Los escalones de monto y plazo deben ser positivos");
        }
    }

    /**
     * Valida el monto contra el producto y contra lo que al distribuidor le queda de línea.
     *
     * <p>Los dos topes se aplican juntos porque responden preguntas distintas: el del producto
     * dice cuánto es prudente prestarle a una persona, el de la línea dice cuánto le queda a él.
     * Basta con que uno se pase para que la colocación no exista.
     */
    public void validateAmount(BigDecimal amount, BigDecimal availableLine) {
        if (amount == null) {
            throw new PlacementValidationException("El monto de la colocación es obligatorio");
        }
        if (amount.compareTo(minAmount) < 0) {
            throw new PlacementValidationException(
                    "El monto mínimo de una colocación es " + minAmount + ", fue: " + amount);
        }
        if (amount.compareTo(maxAmount) > 0) {
            throw new PlacementValidationException(
                    "El máximo que puedes colocarle a una persona es " + maxAmount + ", fue: " + amount);
        }
        if (amount.remainder(BigDecimal.valueOf(amountStep)).signum() != 0) {
            throw new PlacementValidationException(
                    "El monto debe ser múltiplo de " + amountStep + ", fue: " + amount);
        }
        if (availableLine != null && amount.compareTo(availableLine) > 0) {
            throw new InsufficientLineException(amount, availableLine);
        }
    }

    public void validateTerm(int termFortnights) {
        if (termFortnights < minTerm || termFortnights > maxTerm) {
            throw new PlacementValidationException(
                    "El plazo debe estar entre " + minTerm + " y " + maxTerm
                            + " quincenas, fue: " + termFortnights);
        }
        if ((termFortnights - minTerm) % termStep != 0) {
            throw new PlacementValidationException(
                    "El plazo debe avanzar de " + termStep + " en " + termStep
                            + " desde " + minTerm + ", fue: " + termFortnights);
        }
    }
}

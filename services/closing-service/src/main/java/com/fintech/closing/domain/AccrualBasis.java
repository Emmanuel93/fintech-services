package com.fintech.closing.domain;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;

/**
 * La convención con la que se devenga interés. <b>Declarada por producto, no heredada del servicio.</b>
 *
 * <p>Existe porque en la plataforma había dos convenciones conviviendo sin que nada lo dijera: el
 * plan de pagos reparte el interés con {@code tasa / periodosPorAño} —30/360 implícito— y el
 * devengo diario aplica {@code tasa / 360} por día natural. Sobre un crédito de $20,000 al 32 %
 * eso es $533.33 en la cuota del plan contra $551.18 devengados en marzo y $497.84 en febrero.
 *
 * <p>Ninguna de las dos está mal —las dos existen en la industria—. Lo que estaba mal era que no
 * estuviera declarada, y que el desvío no rompiera nada porque cada pieza cuadraba consigo misma.
 */
public enum AccrualBasis {

    /** Días naturales sobre año de 360. Es lo que hace hoy {@code charges}. */
    ACTUAL_360(360) {
        @Override
        public int daysBetween(LocalDate from, LocalDate to) {
            return (int) java.time.temporal.ChronoUnit.DAYS.between(from, to);
        }
    },

    /**
     * Todos los meses valen 30 días. Hace que el devengo y el plan de pagos <b>coincidan al
     * centavo</b>, a cambio de cobrar 30 días en febrero y en marzo por igual.
     */
    THIRTY_360(360) {
        @Override
        public int daysBetween(LocalDate from, LocalDate to) {
            // Convención 30/360 US (Bond Basis).
            int d1 = Math.min(from.getDayOfMonth(), 30);
            int d2 = to.getDayOfMonth();
            if (d1 == 30 && d2 == 31) d2 = 30;
            return (to.getYear() - from.getYear()) * 360
                    + (to.getMonthValue() - from.getMonthValue()) * 30
                    + (d2 - d1);
        }
    },

    /** Días naturales sobre año de 365. Ni una ni otra: hay que elegirla a propósito. */
    ACTUAL_365(365) {
        @Override
        public int daysBetween(LocalDate from, LocalDate to) {
            return (int) java.time.temporal.ChronoUnit.DAYS.between(from, to);
        }
    };

    private final int daysInYear;

    AccrualBasis(int daysInYear) { this.daysInYear = daysInYear; }

    public int daysInYear() { return daysInYear; }

    /** Cuántos días de devengo hay entre dos fechas, según esta convención. */
    public abstract int daysBetween(LocalDate from, LocalDate to);

    /**
     * El interés de un tramo.
     *
     * @param annualRate tasa anual como <b>fracción</b> (0.32 = 32 %), que es la convención del
     *                   monorepo — {@code nominal_rate} se guarda como 0.3200 y charges la usa tal cual.
     */
    public BigDecimal interest(BigDecimal principal, BigDecimal annualRate,
                                LocalDate from, LocalDate to) {
        int days = daysBetween(from, to);
        if (days <= 0 || principal == null || principal.signum() <= 0) return BigDecimal.ZERO;
        return principal.multiply(annualRate)
                .multiply(BigDecimal.valueOf(days))
                .divide(BigDecimal.valueOf(daysInYear), MathContext.DECIMAL128)
                .setScale(2, java.math.RoundingMode.HALF_UP);
    }

    /** El interés de un solo día, que es como lo aplica el cierre. */
    public BigDecimal dailyInterest(BigDecimal principal, BigDecimal annualRate, LocalDate day) {
        return interest(principal, annualRate, day.minusDays(1), day);
    }
}

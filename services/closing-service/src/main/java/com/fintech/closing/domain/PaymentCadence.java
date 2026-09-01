package com.fintech.closing.domain;

import java.time.LocalDate;

/**
 * La cadencia de pago del producto, y cómo avanza una fecha.
 *
 * <p>Réplica deliberada de la que usa {@code AmortizationEngine} en cartera. <b>No se comparte
 * código con cartera a propósito</b>: el cierre no depende de sus clases, y si algún día cartera
 * cambia su cadencia sin avisar, esta diferencia sale en el E2E en vez de propagarse en silencio.
 */
public enum PaymentCadence {

    WEEKLY(52)   { @Override public LocalDate advance(LocalDate from, int periods) { return from.plusWeeks(periods); } },
    BIWEEKLY(26) { @Override public LocalDate advance(LocalDate from, int periods) { return from.plusWeeks(2L * periods); } },
    MONTHLY(12)  { @Override public LocalDate advance(LocalDate from, int periods) { return from.plusMonths(periods); } };

    private final int periodsPerYear;

    PaymentCadence(int periodsPerYear) { this.periodsPerYear = periodsPerYear; }

    public int periodsPerYear() { return periodsPerYear; }

    public abstract LocalDate advance(LocalDate from, int periods);

    /** Mensual por defecto: es lo que hace cartera cuando el producto no la declara. */
    public static PaymentCadence from(String value) {
        if (value == null || value.isBlank()) return MONTHLY;
        return switch (value.trim().toUpperCase()) {
            case "WEEKLY"   -> WEEKLY;
            case "BIWEEKLY" -> BIWEEKLY;
            default          -> MONTHLY;
        };
    }
}

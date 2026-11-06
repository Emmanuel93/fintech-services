package com.fintech.closing.domain;

import java.time.LocalDate;
import java.util.Optional;

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
    /**
     * The fortnight (24 a year) on its default days, the 15th and the month's last day. Moving {@code periods} periods
     * from a date lands on the {@code periods}-th cutoff strictly after it. A product that cuts on other days (the
     * distributor line: the 6th and the 21st) derives its cutoffs from its own {@link SemiMonthlyCalendar}.
     */
    SEMI_MONTHLY(24) {
        @Override public LocalDate advance(LocalDate from, int periods) {
            return SemiMonthlyCalendar.CALENDAR_FORTNIGHT.advance(from, periods);
        }
    },
    MONTHLY(12)  { @Override public LocalDate advance(LocalDate from, int periods) { return from.plusMonths(periods); } };

    private final int periodsPerYear;

    PaymentCadence(int periodsPerYear) { this.periodsPerYear = periodsPerYear; }

    public int periodsPerYear() { return periodsPerYear; }

    public abstract LocalDate advance(LocalDate from, int periods);

    /**
     * La cadencia declarada, o <b>vacío si no hay ninguna reconocible</b>.
     *
     * <p>Antes devolvía {@code MONTHLY} ante nulo <b>y</b> ante basura, con lo que «no configurado» y
     * «configurado mensual» producían el mismo resultado siendo hechos distintos. Un producto
     * configurado semanal cuyo perfil llegara sin el dato cortaba mensual y <b>nada fallaba</b>.
     *
     * <p>Devolver {@code Optional} obliga al llamador a decidir qué hace con la ausencia — y lo que
     * hace es caer a mensual <b>diciéndolo en el log</b>, que es distinto de caer en silencio.
     */
    public static Optional<PaymentCadence> parse(String value) {
        if (value == null || value.isBlank()) return Optional.empty();
        return switch (value.trim().toUpperCase()) {
            case "WEEKLY"       -> Optional.of(WEEKLY);
            case "BIWEEKLY"     -> Optional.of(BIWEEKLY);
            case "SEMI_MONTHLY" -> Optional.of(SEMI_MONTHLY);
            case "MONTHLY"      -> Optional.of(MONTHLY);
            default             -> Optional.empty();
        };
    }
}

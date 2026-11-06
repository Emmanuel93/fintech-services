package com.fintech.statement.application.service.assembly;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Cómo se escriben los números y las fechas en el documento: pesos y fechas de México.
 */
public final class Formats {

    private Formats() {}

    public static final Locale MX = Locale.forLanguageTag("es-MX");

    private static final DateTimeFormatter SHORT = DateTimeFormatter.ofPattern("d MMM yyyy", MX);
    private static final DateTimeFormatter DAY_MONTH = DateTimeFormatter.ofPattern("d 'de' MMMM", MX);
    private static final DateTimeFormatter LONG = DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy", MX);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("d MMM yyyy HH:mm", MX);

    public static String money(BigDecimal amount) {
        NumberFormat f = NumberFormat.getCurrencyInstance(MX);
        return f.format(amount == null ? BigDecimal.ZERO : amount.setScale(2, RoundingMode.HALF_UP));
    }

    /** Una tasa guardada como fracción (0.60) se lee como porcentaje anual (60.00 %). */
    public static String rate(BigDecimal fraction) {
        if (fraction == null) return "—";
        BigDecimal pct = fraction.compareTo(BigDecimal.ONE) <= 0 ? fraction.movePointRight(2) : fraction;
        return pct.setScale(2, RoundingMode.HALF_UP).toPlainString() + " % anual";
    }

    /** A share kept as a fraction (0.18) as a percentage without trailing zeros: «18 %», «12.5 %». */
    public static String share(BigDecimal fraction) {
        if (fraction == null) return "—";
        BigDecimal pct = fraction.movePointRight(2).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros();
        return pct.toPlainString() + " %";
    }

    public static String date(LocalDate d) {
        return d == null ? "—" : SHORT.format(d).replace(".", "");
    }

    public static String date(Instant i, ZoneId zone) {
        return i == null ? "—" : date(i.atZone(zone).toLocalDate());
    }

    public static String stamp(Instant i, ZoneId zone) {
        return i == null ? "—" : STAMP.format(i.atZone(zone)).replace(".", "");
    }

    /** «1 al 15 de noviembre de 2026», «16 de octubre al 15 de noviembre de 2026». */
    public static String period(LocalDate from, LocalDate to) {
        if (from.getYear() == to.getYear() && from.getMonth() == to.getMonth()) {
            return from.getDayOfMonth() + " al " + LONG.format(to);
        }
        if (from.getYear() == to.getYear()) {
            return DAY_MONTH.format(from) + " al " + LONG.format(to);
        }
        return LONG.format(from) + " al " + LONG.format(to);
    }

    public static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
}

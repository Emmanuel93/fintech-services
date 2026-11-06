package com.fintech.accounting.infrastructure.adapter.out.persistence;

import java.math.BigDecimal;
import java.sql.Date;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** How the close's checks read their SQL rows and say their figures, in the words an operator reads. */
final class CheckFormat {

    private static final Locale MX = Locale.forLanguageTag("es-MX");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", MX);

    private CheckFormat() { }

    static String money(BigDecimal v) {
        return NumberFormat.getCurrencyInstance(MX).format(v == null ? BigDecimal.ZERO : v);
    }

    static long num(Object o) {
        return o == null ? 0 : ((Number) o).longValue();
    }

    static BigDecimal dec(Object o) {
        if (o == null) return BigDecimal.ZERO;
        if (o instanceof BigDecimal b) return b;
        return new BigDecimal(o.toString());
    }

    /** «el 1 dic 2026», or «del 1 al 3 dic 2026», or «después del cierre» when the day is not known yet. */
    static String span(Object first, Object last) {
        LocalDate a = day(first), b = day(last);
        if (a == null) return "después del cierre";
        return a.equals(b) || b == null ? "el " + DAY.format(a) : "del " + DAY.format(a) + " al " + DAY.format(b);
    }

    private static LocalDate day(Object o) {
        if (o instanceof Date d) return d.toLocalDate();
        if (o instanceof LocalDate d) return d;
        return null;
    }
}

package com.fintech.shared.selfcheck;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.List;
import java.util.Locale;

/** How a self-check writes its detail: pesos as an operator reads them, and a few examples of what is off. */
public final class SelfCheckText {

    private static final int SAMPLE = 4;

    private SelfCheckText() {
    }

    /** {@code $1,234.50}; nothing is zero. */
    public static String money(BigDecimal value) {
        DecimalFormat f = new DecimalFormat("$#,##0.00", DecimalFormatSymbols.getInstance(Locale.US));
        return f.format(value == null ? BigDecimal.ZERO : value);
    }

    /** The first few items, and an ellipsis when there are more. */
    public static String sample(List<String> items) {
        String head = String.join("; ", items.subList(0, Math.min(SAMPLE, items.size())));
        return items.size() > SAMPLE ? head + "; …" : head;
    }
}

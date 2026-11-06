package com.fintech.beneficiary.domain;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The portfolio's reasons to refuse a placement to a client, in the distributor's words.
 *
 * <p>The portfolio answers in codes with a Spanish detail ({@code AMOUNT_ABOVE_CLIENT_LIMIT: cliente NUEVO hasta 5000
 * por préstamo, pidió 8000}); several reasons come joined by {@code "; "}. A red-list mark is explained only when
 * every mark on the client is hers (her report, her line's arrears): what another distributor said about him is not
 * hers to see yet, so she reads that Kredius cannot lend to him today, and nothing more.
 */
public final class PlacementRefusal {

    private static final NumberFormat MONEY = NumberFormat.getCurrencyInstance(Locale.of("es", "MX"));
    private static final Pattern AMOUNT = Pattern.compile("cliente (\\w+) hasta ([0-9.]+) por préstamo");
    private static final Pattern OUTSTANDING = Pattern.compile("cliente (\\w+) hasta ([0-9.]+) de saldo, debe ([0-9.]+)");
    private static final Pattern LOANS = Pattern.compile("cliente (\\w+) hasta ([0-9]+) préstamos vivos, ya tiene ([0-9]+)");
    private static final Pattern TERM = Pattern.compile("cliente (\\w+) hasta ([0-9]+) quincenas");

    public static final String RED_LISTED = "CLIENT_RED_LISTED";
    public static final String GENERIC_RED_LIST = "Por ahora Kredius no puede prestarle a este cliente";
    private static final String AMOUNT_ABOVE_CLIENT_LIMIT = "AMOUNT_ABOVE_CLIENT_LIMIT";
    private static final String CLIENT_OUTSTANDING_LIMIT = "CLIENT_OUTSTANDING_LIMIT";
    private static final String CLIENT_ACTIVE_LOANS_LIMIT = "CLIENT_ACTIVE_LOANS_LIMIT";
    private static final String TERM_ABOVE_CLIENT_LIMIT = "TERM_ABOVE_CLIENT_LIMIT";

    private PlacementRefusal() {}

    /** Whether the reason is about the client (his type's limits or the red list), not about her line or the product. */
    public static boolean isAboutTheClient(String reason) {
        if (reason == null) return false;
        return reason.startsWith(RED_LISTED) || isTypeLimit(reason);
    }

    /**
     * Each reason once, without noise: on the red list his type's limits are left out (nothing is lent to him, a
     * smaller loan would not go either), and what he owes is said only when he owes something.
     *
     * @param marksAreHers every red-list mark on him is hers: only then is the red list explained
     */
    public static String inHerWords(String reason, boolean marksAreHers) {
        if (reason == null || reason.isBlank()) return "Cartera no autorizó la colocación";
        List<String> reasons = new ArrayList<>();
        for (String one : reason.split(";\\s*")) if (!one.isBlank()) reasons.add(one.trim());
        boolean redListed = reasons.stream().anyMatch(r -> r.startsWith(RED_LISTED));
        boolean amountAbove = reasons.stream().anyMatch(r -> r.startsWith(AMOUNT_ABOVE_CLIENT_LIMIT));
        List<String> parts = new ArrayList<>();
        for (String one : reasons) {
            if (redListed && isTypeLimit(one)) continue;
            String said = one(one, marksAreHers, amountAbove);
            if (said != null) parts.add(said);
        }
        return parts.isEmpty() ? "Cartera no autorizó la colocación" : String.join(". ", parts);
    }

    private static boolean isTypeLimit(String reason) {
        return reason.startsWith(AMOUNT_ABOVE_CLIENT_LIMIT) || reason.startsWith(CLIENT_OUTSTANDING_LIMIT)
                || reason.startsWith(CLIENT_ACTIVE_LOANS_LIMIT) || reason.startsWith(TERM_ABOVE_CLIENT_LIMIT);
    }

    /** One reason in her words; {@code null} when another reason already says it. */
    private static String one(String r, boolean marksAreHers, boolean amountAbove) {
        if (r.startsWith(RED_LISTED)) {
            if (!marksAreHers) return GENERIC_RED_LIST;
            String why = r.contains(":") ? r.substring(r.indexOf(':') + 1).trim() : "";
            List<String> reasons = new ArrayList<>();
            if (why.contains("DISTRIBUTOR_REPORT")) reasons.add("lo reportaste");
            if (why.contains("VOUCHER_IN_ARREARS")) reasons.add("tiene cuotas vencidas");
            if (why.contains("DISTRIBUTOR_IN_ARREARS")) reasons.add("tu línea tiene pagos vencidos");
            return "Tu cliente está en lista roja" + (reasons.isEmpty() ? "" : " (" + String.join(" y ", reasons) + ")")
                    + ": por ahora no se le puede prestar";
        }
        Matcher m;
        if (r.startsWith(AMOUNT_ABOVE_CLIENT_LIMIT) && (m = AMOUNT.matcher(r)).find()) {
            return "A un cliente " + type(m.group(1)) + " le puedes prestar hasta " + money(m.group(2)) + " por préstamo";
        }
        if (r.startsWith(CLIENT_OUTSTANDING_LIMIT) && (m = OUTSTANDING.matcher(r)).find()) {
            String limit = "a un cliente " + type(m.group(1)) + " le puedes prestar hasta " + money(m.group(2))
                    + " sumando sus préstamos";
            if (new BigDecimal(m.group(3)).signum() > 0) return "Tu cliente ya te debe " + money(m.group(3)) + ": " + limit;
            // He owes nothing: the loan alone is over his balance limit, never below his per-loan one (sales-org
            // keeps it so at every level), so the per-loan limit already said it.
            return amountAbove ? null : "A" + limit.substring(1);
        }
        if (r.startsWith(CLIENT_ACTIVE_LOANS_LIMIT) && (m = LOANS.matcher(r)).find()) {
            int max = Integer.parseInt(m.group(2));
            return "Un cliente " + type(m.group(1)) + " puede tener hasta " + max + (max == 1 ? " préstamo vivo" : " préstamos vivos")
                    + " contigo y ya tiene " + m.group(3);
        }
        if (r.startsWith(TERM_ABOVE_CLIENT_LIMIT) && (m = TERM.matcher(r)).find()) {
            return "A un cliente " + type(m.group(1)) + " le puedes prestar a lo más a " + m.group(2) + " quincenas";
        }
        return "Cartera no autorizó la colocación (" + r + ")";
    }

    private static String type(String code) {
        return "REVOLVENTE".equalsIgnoreCase(code) ? "revolvente" : "nuevo";
    }

    private static String money(String amount) {
        return MONEY.format(new BigDecimal(amount));
    }
}

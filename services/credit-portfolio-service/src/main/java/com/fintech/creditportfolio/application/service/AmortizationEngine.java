package com.fintech.creditportfolio.application.service;

import com.fintech.creditportfolio.domain.Installment;
import com.fintech.creditportfolio.domain.config.SemiMonthlyCalendar;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.IntFunction;

/**
 * Config-driven amortisation engine — the strategy and payment cadence are chosen from the
 * product config version (amortizationType + paymentFrequency), not hardcoded.
 *
 * <ul>
 *   <li>FRENCH (AE-02): fixed instalment (cuota fija)</li>
 *   <li>GERMAN (AE-03): fixed principal, decreasing interest</li>
 *   <li>BULLET: periodic interest only, full principal at maturity</li>
 * </ul>
 *
 * Payment frequency drives the rate-per-period and due-date increments:
 * WEEKLY (52/yr), BIWEEKLY (26/yr), SEMI_MONTHLY (24/yr: two fixed days a month, the product's cutoff days — the
 * distributor line pays on the 6th and the 21st; without days, the 15th and the last day), MONTHLY (12/yr).
 */
@Component
public class AmortizationEngine {

    /**
     * IVA por defecto cuando el llamador no fija uno. Es el nacional; la frontera tiene estímulo.
     *
     * <p>La tasa se recibe por parámetro y no se lee de la configuración del servicio porque
     * <b>depende de la sucursal que coloca</b>: la Región Fronteriza tiene una tasa distinta de la
     * del resto del país. Un valor único en el servicio obligaría a desplegar una instancia por
     * zona, que es exactamente lo que no se quiere.
     */
    public static final BigDecimal IVA_NACIONAL = new BigDecimal("0.16");

    private static final MathContext MC = MathContext.DECIMAL128;
    private static final int SCALE = 2;

    /** Periods per year + date stepping for each supported frequency. */
    private enum Cadence {
        WEEKLY(52),
        BIWEEKLY(26),
        SEMI_MONTHLY(24),
        MONTHLY(12);

        final int periodsPerYear;
        Cadence(int p) { this.periodsPerYear = p; }

        LocalDate advance(LocalDate from, int periodsFromStart, SemiMonthlyCalendar fortnight) {
            return switch (this) {
                case WEEKLY       -> from.plusWeeks(periodsFromStart);
                case BIWEEKLY     -> from.plusWeeks(2L * periodsFromStart);
                case SEMI_MONTHLY -> fortnight.advance(from, periodsFromStart);
                case MONTHLY      -> from.plusMonths(periodsFromStart);
            };
        }

        static Cadence from(String paymentFrequency) {
            if (paymentFrequency == null) return MONTHLY;
            return switch (paymentFrequency.toUpperCase()) {
                case "WEEKLY"       -> WEEKLY;
                case "BIWEEKLY"     -> BIWEEKLY;
                case "SEMI_MONTHLY" -> SEMI_MONTHLY;
                default              -> MONTHLY;
            };
        }
    }

    // ── The fortnight ──────────────────────────────────────────────────────
    // Two cutoffs a month on the product's days (SemiMonthlyCalendar). Closing derives the cutoffs with its own copy of
    // the calendar (it does not depend on this class on purpose): a disagreement shows in the end-to-end run.

    /**
     * The share of its first fortnight a plan that starts on {@code start} bears, in (0, 1]. A placement made on the
     * 16th has the money five of the fifteen days of the fortnight that ends on the 21st: its first period bears a
     * third of the fortnight's interest. One made on a cutoff day starts paying at the next one, a whole fortnight.
     */
    static BigDecimal firstFortnightShare(LocalDate start, LocalDate firstDue, SemiMonthlyCalendar fortnight) {
        if (start == null || firstDue == null || !start.isBefore(firstDue) || !fortnight.isCutoff(firstDue)) {
            return BigDecimal.ONE;
        }
        LocalDate previous = fortnight.previousCutoff(firstDue);
        long length = firstDue.toEpochDay() - previous.toEpochDay();
        long held = firstDue.toEpochDay() - start.toEpochDay();
        return held >= length ? BigDecimal.ONE
                : BigDecimal.valueOf(held).divide(BigDecimal.valueOf(length), MC);
    }

    /**
     * El primer vencimiento: un período de la cadencia después de {@code from}.
     *
     * <p>Existe para que los llamadores dejen de escribir {@code plusMonths(1)} a mano. Un
     * calendario quincenal cuyo primer pago cae en un mes no es un detalle cosmético: corre todo
     * el plan y, con él, la fecha en que la cuenta empieza a envejecer.
     */
    public static LocalDate firstDueDate(LocalDate from, String paymentFrequency) {
        return firstDueDate(from, paymentFrequency, SemiMonthlyCalendar.CALENDAR_FORTNIGHT);
    }

    /** The first due date of a plan on the product's fortnight: its first cutoff strictly after {@code from}. */
    public static LocalDate firstDueDate(LocalDate from, String paymentFrequency, SemiMonthlyCalendar fortnight) {
        return Cadence.from(paymentFrequency).advance(from, 1, fortnight);
    }

    /**
     * {@code from} moved {@code periods} periods of the cadence, counted from {@code from} in one step: moving a
     * month at a time drifts the day (31 Jan → 28 Feb → 28 Mar), counting from the anchor does not.
     */
    public static LocalDate shift(LocalDate from, String paymentFrequency, int periods) {
        return shift(from, paymentFrequency, periods, SemiMonthlyCalendar.CALENDAR_FORTNIGHT);
    }

    /** {@code from} moved {@code periods} periods, on the product's fortnight when it pays by fortnights. */
    public static LocalDate shift(LocalDate from, String paymentFrequency, int periods, SemiMonthlyCalendar fortnight) {
        return periods <= 0 ? from : Cadence.from(paymentFrequency).advance(from, periods, fortnight);
    }

    /**
     * The first date of the plan's cadence, counted from {@code anchor}, that falls after {@code today}. A new plan
     * that keeps the old one's day of the month keeps the statement cutoffs aligned (closing derives an
     * installment loan's cutoffs from the same cadence).
     */
    public static LocalDate nextDueAfter(LocalDate anchor, LocalDate today, String paymentFrequency) {
        return nextDueAfter(anchor, today, paymentFrequency, SemiMonthlyCalendar.CALENDAR_FORTNIGHT);
    }

    /** The first date of the plan's cadence after {@code today}, on the product's fortnight when it pays by them. */
    public static LocalDate nextDueAfter(LocalDate anchor, LocalDate today, String paymentFrequency,
                                         SemiMonthlyCalendar fortnight) {
        Cadence cadence = Cadence.from(paymentFrequency);
        if (cadence == Cadence.SEMI_MONTHLY) {
            // Fixed days: the first cutoff after today, whatever the anchor's day was.
            return fortnight.advance(today.isBefore(anchor) ? anchor.minusDays(1) : today, 1);
        }
        LocalDate d = anchor;
        int n = 0;
        while (!d.isAfter(today)) {
            d = cadence.advance(anchor, ++n, fortnight);
        }
        return d;
    }

    /**
     * Generates the schedule for an installment loan, choosing the method from the config.
     *
     * @param scheduleId        parent schedule UUID
     * @param principal         approved loan amount
     * @param nominalRateAnnual tasa nominal anual como <b>fracción</b> (0.24 = 24%)
     *
     * <p>Fracción y no porcentaje porque es la convención de todo el sistema: {@code nominal_rate}
     * se guarda como 0.2400 y charges-service la usa tal cual para devengar. El motor era el único
     * que esperaba 24.0 y dividía entre 100, y sus dos llamadores le pasaban la fracción de la base:
     * el resultado era un plan de pagos con <b>cien veces menos interés</b> del real —$8.33 donde
     * tocaban $833.33— mientras el devengo cobraba lo correcto. El cliente veía un plan que no
     * correspondía a su deuda, y nada fallaba.
     * @param termPeriods       number of payment periods
     * @param amortizationType  FRENCH | GERMAN | BULLET (null → FRENCH)
     * @param paymentFrequency  WEEKLY | BIWEEKLY | MONTHLY (null → MONTHLY)
     * @param firstPaymentDate  date of first payment
     */
    public List<Installment> generate(UUID scheduleId, BigDecimal principal,
                                       BigDecimal nominalRateAnnual, int termPeriods,
                                       String amortizationType, String paymentFrequency,
                                       LocalDate firstPaymentDate) {
        return generate(scheduleId, principal, nominalRateAnnual, termPeriods, amortizationType,
                paymentFrequency, firstPaymentDate, IVA_NACIONAL);
    }

    /**
     * @param vatRate IVA a trasladar sobre el interés; cero si el acreditado va exento.
     *
     * <p><b>La cuota sale plana con el IVA dentro.</b> La anualidad se resuelve sobre la tasa
     * incrementada por el impuesto, así el importe que se le cobra al cliente no cambia mes a mes.
     * Con el IVA encima de una anualidad financiera el cobro baja un poco cada período —el impuesto
     * decrece con el interés— y eso rompe el descuento de nómina, que es un importe fijo pactado con
     * el patrón, y contradice cómo se anuncia el crédito: «12 mensualidades de $4,821».
     *
     * <p>No cambia el tratamiento fiscal: el IVA sigue siendo {@code interés × tasa} sobre el interés
     * real del período. Lo único que cambia es cuánto capital amortiza cada cuota.
     */
    public List<Installment> generate(UUID scheduleId, BigDecimal principal,
                                       BigDecimal nominalRateAnnual, int termPeriods,
                                       String amortizationType, String paymentFrequency,
                                       LocalDate firstPaymentDate, BigDecimal vatRate) {
        return generate(scheduleId, principal, nominalRateAnnual, termPeriods, amortizationType, paymentFrequency,
                null, firstPaymentDate, vatRate);
    }

    /**
     * A plan that knows the day the money was delivered.
     *
     * <p>Only the calendar fortnight uses it: its periods end on fixed days, so the first one is as long as the days
     * between the delivery and the first cutoff. That first period bears its share of the fortnight's interest
     * ({@link #firstFortnightShare}) and every installment stays the same amount: the annuity is solved over the
     * shorter first period. The other cadences start a whole period after the delivery and are unchanged.
     *
     * @param startDate the day the money was delivered; null for a whole first period
     */
    public List<Installment> generate(UUID scheduleId, BigDecimal principal,
                                       BigDecimal nominalRateAnnual, int termPeriods,
                                       String amortizationType, String paymentFrequency,
                                       LocalDate startDate, LocalDate firstPaymentDate, BigDecimal vatRate) {
        return generate(scheduleId, principal, nominalRateAnnual, termPeriods, amortizationType, paymentFrequency,
                startDate, firstPaymentDate, vatRate, SemiMonthlyCalendar.CALENDAR_FORTNIGHT);
    }

    /**
     * A plan on the product's fortnight: its installments fall on the fortnight's two cutoff days (the distributor
     * line: the 6th and the 21st), the first one on {@code firstPaymentDate}.
     *
     * @param fortnight the product's two cutoff days; only a SEMI_MONTHLY plan reads it
     */
    public List<Installment> generate(UUID scheduleId, BigDecimal principal,
                                       BigDecimal nominalRateAnnual, int termPeriods,
                                       String amortizationType, String paymentFrequency,
                                       LocalDate startDate, LocalDate firstPaymentDate, BigDecimal vatRate,
                                       SemiMonthlyCalendar fortnight) {

        Cadence cadence = Cadence.from(paymentFrequency);
        SemiMonthlyCalendar q = fortnight != null ? fortnight : SemiMonthlyCalendar.CALENDAR_FORTNIGHT;
        BigDecimal iva = vatRate != null ? vatRate : BigDecimal.ZERO;
        BigDecimal r = nominalRateAnnual
                .divide(BigDecimal.valueOf(cadence.periodsPerYear), MC);
        BigDecimal firstRate = cadence == Cadence.SEMI_MONTHLY
                ? r.multiply(firstFortnightShare(startDate, firstPaymentDate, q), MC)
                : r;
        IntFunction<LocalDate> dueOf = i -> cadence.advance(firstPaymentDate, i - 1, q);

        String method = amortizationType == null ? "FRENCH" : amortizationType.toUpperCase();
        return switch (method) {
            case "GERMAN" -> german(scheduleId, principal, r, firstRate, termPeriods, dueOf, iva);
            case "BULLET" -> bullet(scheduleId, principal, r, firstRate, termPeriods, dueOf, iva);
            default        -> french(scheduleId, principal, r, firstRate, termPeriods, dueOf, iva);
        };
    }

    /** Backwards-compatible monthly FRENCH entry point. */
    public List<Installment> generateFrench(UUID scheduleId, BigDecimal principal,
                                            BigDecimal nominalRateAnnual, int termMonths,
                                            LocalDate firstPaymentDate) {
        return generate(scheduleId, principal, nominalRateAnnual, termMonths,
                "FRENCH", "MONTHLY", firstPaymentDate);
    }

    // ── FRENCH: fixed payment ───────────────────────────────────────────────

    private List<Installment> french(UUID scheduleId, BigDecimal principal, BigDecimal r, BigDecimal firstRate,
                                     int n, IntFunction<LocalDate> dueOf, BigDecimal iva) {
        // La anualidad se resuelve sobre la tasa incrementada por el IVA: así el importe cobrado
        // —capital + interés + impuesto— es el mismo todos los meses. Resolverla sobre la tasa
        // financiera y sumar el IVA encima deja un cobro que decrece con el interés.
        BigDecimal withTax = r.multiply(BigDecimal.ONE.add(iva), MC);
        BigDecimal pmt = fixedPayment(principal, withTax, n);
        if (firstRate.compareTo(r) != 0) {
            // A shorter first period: the same payment every period, solved over it. Discounting the first payment at
            // its own rate gives the annuity times (1 + first) / (1 + r), both with the tax.
            BigDecimal firstWithTax = firstRate.multiply(BigDecimal.ONE.add(iva), MC);
            pmt = fixedPaymentExact(principal, withTax, n)
                    .multiply(BigDecimal.ONE.add(firstWithTax, MC), MC)
                    .divide(BigDecimal.ONE.add(withTax, MC), MC)
                    .setScale(SCALE, RoundingMode.HALF_UP);
        }
        List<Installment> out = new ArrayList<>();
        BigDecimal balance = principal;
        for (int i = 1; i <= n; i++) {
            BigDecimal interest = balance.multiply(i == 1 ? firstRate : r, MC).setScale(SCALE, RoundingMode.HALF_UP);
            BigDecimal tax = iva(interest, iva);
            BigDecimal principalI = (i == n)
                    ? balance                                  // clear rounding residue
                    : pmt.subtract(interest).subtract(tax).setScale(SCALE, RoundingMode.HALF_UP);
            out.add(Installment.of(scheduleId, i, dueOf.apply(i), principalI, interest, tax));
            balance = balance.subtract(principalI);
        }
        return out;
    }

    // ── GERMAN: fixed principal, decreasing interest ────────────────────────

    private List<Installment> german(UUID scheduleId, BigDecimal principal, BigDecimal r, BigDecimal firstRate,
                                     int n, IntFunction<LocalDate> dueOf, BigDecimal iva) {
        BigDecimal fixedPrincipal = principal.divide(BigDecimal.valueOf(n), SCALE, RoundingMode.DOWN);
        List<Installment> out = new ArrayList<>();
        BigDecimal balance = principal;
        for (int i = 1; i <= n; i++) {
            BigDecimal interest = balance.multiply(i == 1 ? firstRate : r, MC).setScale(SCALE, RoundingMode.HALF_UP);
            BigDecimal principalI = (i == n) ? balance : fixedPrincipal;   // last clears residue
            out.add(Installment.of(scheduleId, i, dueOf.apply(i), principalI, interest,
                    iva(interest, iva)));
            balance = balance.subtract(principalI);
        }
        return out;
    }

    // ── BULLET: interest only, principal at maturity ────────────────────────

    private List<Installment> bullet(UUID scheduleId, BigDecimal principal, BigDecimal r, BigDecimal firstRate,
                                     int n, IntFunction<LocalDate> dueOf, BigDecimal iva) {
        List<Installment> out = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            BigDecimal interest = principal.multiply(i == 1 ? firstRate : r, MC).setScale(SCALE, RoundingMode.HALF_UP);
            BigDecimal principalI = (i == n) ? principal : BigDecimal.ZERO.setScale(SCALE);
            out.add(Installment.of(scheduleId, i, dueOf.apply(i), principalI, interest,
                    iva(interest, iva)));
        }
        return out;
    }

    /** El IVA de un interés, redondeado como se cobra: al centavo. */
    private BigDecimal iva(BigDecimal interest, BigDecimal rate) {
        return interest.multiply(rate, MC).setScale(SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal fixedPayment(BigDecimal principal, BigDecimal r, int n) {
        return fixedPaymentExact(principal, r, n).setScale(SCALE, RoundingMode.HALF_UP);
    }

    /** The annuity before rounding: what a shorter first period scales before it is rounded once. */
    private BigDecimal fixedPaymentExact(BigDecimal principal, BigDecimal r, int n) {
        if (r.compareTo(BigDecimal.ZERO) == 0) {
            return principal.divide(BigDecimal.valueOf(n), MC);
        }
        BigDecimal onePlusR = BigDecimal.ONE.add(r, MC);
        BigDecimal factor   = BigDecimal.ONE.subtract(onePlusR.pow(-n, MC), MC);
        return principal.multiply(r, MC).divide(factor, MC);
    }
}

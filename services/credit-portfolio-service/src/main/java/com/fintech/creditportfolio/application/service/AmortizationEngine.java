package com.fintech.creditportfolio.application.service;

import com.fintech.creditportfolio.domain.Installment;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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
 * WEEKLY (52/yr), BIWEEKLY (26/yr), MONTHLY (12/yr).
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
        MONTHLY(12);

        final int periodsPerYear;
        Cadence(int p) { this.periodsPerYear = p; }

        LocalDate advance(LocalDate from, int periodsFromStart) {
            return switch (this) {
                case WEEKLY   -> from.plusWeeks(periodsFromStart);
                case BIWEEKLY -> from.plusWeeks(2L * periodsFromStart);
                case MONTHLY  -> from.plusMonths(periodsFromStart);
            };
        }

        static Cadence from(String paymentFrequency) {
            if (paymentFrequency == null) return MONTHLY;
            return switch (paymentFrequency.toUpperCase()) {
                case "WEEKLY"   -> WEEKLY;
                case "BIWEEKLY" -> BIWEEKLY;
                default          -> MONTHLY;
            };
        }
    }

    /**
     * El primer vencimiento: un período de la cadencia después de {@code from}.
     *
     * <p>Existe para que los llamadores dejen de escribir {@code plusMonths(1)} a mano. Un
     * calendario quincenal cuyo primer pago cae en un mes no es un detalle cosmético: corre todo
     * el plan y, con él, la fecha en que la cuenta empieza a envejecer.
     */
    public static LocalDate firstDueDate(LocalDate from, String paymentFrequency) {
        return Cadence.from(paymentFrequency).advance(from, 1);
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

        Cadence cadence = Cadence.from(paymentFrequency);
        BigDecimal iva = vatRate != null ? vatRate : BigDecimal.ZERO;
        BigDecimal r = nominalRateAnnual
                .divide(BigDecimal.valueOf(cadence.periodsPerYear), MC);

        String method = amortizationType == null ? "FRENCH" : amortizationType.toUpperCase();
        return switch (method) {
            case "GERMAN" -> german(scheduleId, principal, r, termPeriods, cadence, firstPaymentDate, iva);
            case "BULLET" -> bullet(scheduleId, principal, r, termPeriods, cadence, firstPaymentDate, iva);
            default        -> french(scheduleId, principal, r, termPeriods, cadence, firstPaymentDate, iva);
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

    private List<Installment> french(UUID scheduleId, BigDecimal principal, BigDecimal r,
                                     int n, Cadence cadence, LocalDate first, BigDecimal iva) {
        // La anualidad se resuelve sobre la tasa incrementada por el IVA: así el importe cobrado
        // —capital + interés + impuesto— es el mismo todos los meses. Resolverla sobre la tasa
        // financiera y sumar el IVA encima deja un cobro que decrece con el interés.
        BigDecimal pmt = fixedPayment(principal, r.multiply(BigDecimal.ONE.add(iva), MC), n);
        List<Installment> out = new ArrayList<>();
        BigDecimal balance = principal;
        for (int i = 1; i <= n; i++) {
            BigDecimal interest = balance.multiply(r, MC).setScale(SCALE, RoundingMode.HALF_UP);
            BigDecimal tax = iva(interest, iva);
            BigDecimal principalI = (i == n)
                    ? balance                                  // clear rounding residue
                    : pmt.subtract(interest).subtract(tax).setScale(SCALE, RoundingMode.HALF_UP);
            out.add(Installment.of(scheduleId, i, cadence.advance(first, i - 1), principalI, interest, tax));
            balance = balance.subtract(principalI);
        }
        return out;
    }

    // ── GERMAN: fixed principal, decreasing interest ────────────────────────

    private List<Installment> german(UUID scheduleId, BigDecimal principal, BigDecimal r,
                                     int n, Cadence cadence, LocalDate first, BigDecimal iva) {
        BigDecimal fixedPrincipal = principal.divide(BigDecimal.valueOf(n), SCALE, RoundingMode.DOWN);
        List<Installment> out = new ArrayList<>();
        BigDecimal balance = principal;
        for (int i = 1; i <= n; i++) {
            BigDecimal interest = balance.multiply(r, MC).setScale(SCALE, RoundingMode.HALF_UP);
            BigDecimal principalI = (i == n) ? balance : fixedPrincipal;   // last clears residue
            out.add(Installment.of(scheduleId, i, cadence.advance(first, i - 1), principalI, interest,
                    iva(interest, iva)));
            balance = balance.subtract(principalI);
        }
        return out;
    }

    // ── BULLET: interest only, principal at maturity ────────────────────────

    private List<Installment> bullet(UUID scheduleId, BigDecimal principal, BigDecimal r,
                                     int n, Cadence cadence, LocalDate first, BigDecimal iva) {
        BigDecimal interest = principal.multiply(r, MC).setScale(SCALE, RoundingMode.HALF_UP);
        List<Installment> out = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            BigDecimal principalI = (i == n) ? principal : BigDecimal.ZERO.setScale(SCALE);
            out.add(Installment.of(scheduleId, i, cadence.advance(first, i - 1), principalI, interest,
                    iva(interest, iva)));
        }
        return out;
    }

    /** El IVA de un interés, redondeado como se cobra: al centavo. */
    private BigDecimal iva(BigDecimal interest, BigDecimal rate) {
        return interest.multiply(rate, MC).setScale(SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal fixedPayment(BigDecimal principal, BigDecimal r, int n) {
        if (r.compareTo(BigDecimal.ZERO) == 0) {
            return principal.divide(BigDecimal.valueOf(n), MC);
        }
        BigDecimal onePlusR = BigDecimal.ONE.add(r, MC);
        BigDecimal factor   = BigDecimal.ONE.subtract(onePlusR.pow(-n, MC), MC);
        return principal.multiply(r, MC).divide(factor, MC).setScale(SCALE, RoundingMode.HALF_UP);
    }
}

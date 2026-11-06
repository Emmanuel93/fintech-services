package com.fintech.closing.application.service;

import com.fintech.closing.domain.AccountCloseProfile;
import com.fintech.closing.domain.ClosePolicy;
import com.fintech.closing.domain.BillingCycle;
import com.fintech.closing.domain.CutoffRule;
import com.fintech.closing.domain.CutoffSchedule;
import com.fintech.closing.domain.PaymentCadence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.time.DayOfWeek;
import java.time.temporal.TemporalAdjusters;
import java.util.Optional;

/**
 * Deriva el calendario de corte de una cuenta. <b>Es propiedad del cierre.</b>
 *
 * <p>Para un producto no revolvente la cadencia coincide con el vencimiento de la cuota, así que la
 * tentación es leer {@code installments.due_date} de cartera. No se hace, por tres razones que no
 * son de estilo:
 *
 * <ol>
 *   <li><b>Aislamiento.</b> Consultar cartera en línea durante la ventana de cierre es exactamente
 *       lo que hoy hace que el barrido nocturno compita con la API del backoffice.</li>
 *   <li><b>El corte es política, no plan.</b> Cortar N días antes del vencimiento, o correrse si cae
 *       inhábil, no cabe en un plan de pagos — que es un documento de cara al cliente.</li>
 *   <li><b>Un corte sellado es inmutable.</b> Si cartera regenera el calendario por una reestructura,
 *       el corte ya emitido no puede cambiar hacia atrás: el ajuste va al ciclo siguiente.</li>
 * </ol>
 *
 * <p>Lo que el cierre necesita —fecha de activación, cadencia y plazo— llega por evento y vive en
 * {@link AccountCloseProfile}. De ahí sale todo lo demás.
 */
@Service
public class CutoffScheduleDeriver {

    private static final Logger log = LoggerFactory.getLogger(CutoffScheduleDeriver.class);

    private final BusinessCalendarService calendar;

    public CutoffScheduleDeriver(BusinessCalendarService calendar) {
        this.calendar = calendar;
    }

    /**
     * El corte del ciclo {@code n} de una cuenta, ya corrido si cae en inhábil.
     *
     * @return vacío si el producto no tiene corte, o si el ciclo excede el plazo del crédito.
     */
    public Optional<CutoffSchedule> derive(AccountCloseProfile profile, ClosePolicy policy, int cycle) {
        // The rules are the product's cycle pinned to the account; the policy table is what accounts
        // opened before the product declared one still use (and the calendar always comes from it).
        BillingCycle rules = profile.billingCycle().orElseGet(() -> BillingCycle.fromPolicy(policy));
        if (rules.cutoffRule() == CutoffRule.NONE || profile.getActivatedOn() == null || cycle < 1) {
            return Optional.empty();
        }
        // Un préstamo a plazo no corta después de su última cuota: ahí ya no hay nada que cortar.
        if (profile.getTermPeriods() != null && !profile.isRevolving() && cycle > profile.getTermPeriods()) {
            return Optional.empty();
        }

        LocalDate crudo = rawDate(profile, rules, cycle);
        if (crudo == null) return Optional.empty();

        String calendarCode = policy.getCalendarCode();
        // A fortnight ends on its day whatever day it is: the cutoff is the period's boundary and the nightly close runs
        // every day. Moving it to the next business day would start the next fortnight a day or two late. Only the
        // payment due date moves.
        boolean fortnight = rules.cutoffRule() == CutoffRule.SEMI_MONTHLY;
        LocalDate cutoff = fortnight ? crudo : calendar.shift(calendarCode, crudo, rules.shift());
        // The fortnight's statement is paid by a fixed day when the product says so (the 18th for the cutoff of the
        // 6th, the 3rd for the one of the 21st); otherwise a number of days after the cutoff.
        LocalDate rawDue = fortnight
                ? rules.semiMonthly().dueDate(cutoff).orElseGet(() -> cutoff.plusDays(rules.paymentDueOffsetDays()))
                : cutoff.plusDays(rules.paymentDueOffsetDays());
        LocalDate paymentDueDate = calendar.shift(calendarCode, rawDue, rules.shift());

        // El corrimiento del corte puede empujarlo más allá de la fecha límite calculada sobre el
        // crudo. La invariante `limite >= corte` la impone la base; se respeta aquí.
        if (paymentDueDate.isBefore(cutoff)) paymentDueDate = cutoff;

        return Optional.of(CutoffSchedule.scheduled(profile.getCreditAccountId(), cycle, cutoff, paymentDueDate));
    }

    /** La fecha antes de aplicar el calendario. Aquí vive la diferencia entre reglas. */
    private LocalDate rawDate(AccountCloseProfile profile, BillingCycle rules, int cycle) {
        LocalDate alta = profile.getActivatedOn();

        return switch (rules.cutoffRule()) {
            // No revolvente: la cadencia del plan, anclada al alta. El primer corte cae un período
            // después de activar — igual que la primera cuota que genera cartera.
            case INSTALLMENT_DUE_DATE -> cadenceOf(profile).advance(alta, cycle);

            // Revolvente: el ciclo avanza con la PERIODICIDAD DEL PRODUCTO —semanal, quincenal o
            // mensual— anclada al DÍA de la activación. Es lo que hace que dos tarjetas activadas el
            // 3 y el 20 corten en días distintos, que es como debe ser.
            //
            // Antes era `alta.plusMonths(cycle)`: mensual a fuerza. Una línea configurada WEEKLY en
            // `credit_product.payment_frequencies` cortaba mensual, y nada fallaba — el calendario se
            // derivaba, se persistía y se sellaba con la periodicidad equivocada. El dato siempre
            // estuvo ahí: el producto lo declara, el evento de activación lo publica y el perfil
            // tiene la columna. Sólo esta línea lo ignoraba.
            case CYCLE_FROM_ACTIVATION -> cadenceOf(profile).advance(alta, cycle);

            // Día fijo del mes, igual para todo el producto. El primer corte es el siguiente que
            // llegue después del alta, no el del mes del alta si ya pasó.
            case DAY_OF_MONTH -> fixedDayOfMonth(alta, rules.cutoffDay(), cycle);

            // A fixed weekday: the first one strictly after activation, then every period of the
            // product's frequency (weekly or biweekly keep the weekday; a monthly frequency would not).
            case DAY_OF_WEEK -> fixedDayOfWeek(alta, rules.cutoffDay(), cadenceOf(profile), cycle);

            // The fortnight: the cycle-th of its two cutoff days (the 6th and the 21st) strictly after the activation,
            // the same days for every account of the product, whatever day each one was activated.
            case SEMI_MONTHLY -> rules.semiMonthly().advance(alta, cycle);

            case NONE -> null;
        };
    }

    private static LocalDate fixedDayOfWeek(LocalDate alta, Integer isoDay, PaymentCadence cadence, int cycle) {
        if (isoDay == null || isoDay < 1 || isoDay > 7) return null;
        LocalDate first = alta.with(TemporalAdjusters.next(DayOfWeek.of(isoDay)));
        return cadence.advance(first, cycle - 1);
    }

    /**
     * La cadencia de la cuenta, gritando cuando se cae al default.
     *
     * <p>Un producto sin periodicidad declarada corta mensual, que es lo que hace cartera al
     * amortizar. Pero se registra en WARN: si una tarjeta configurada semanal llegara sin el dato,
     * el calendario saldría mensual y la única forma de enterarse sería que alguien comparara el
     * catálogo con los cortes sellados a mano.
     */
    private PaymentCadence cadenceOf(AccountCloseProfile profile) {
        if (!profile.hasDeclaredCadence()) {
            log.warn("La cuenta {} (producto {}) no declara periodicidad de pago — su calendario de "
                     + "cortes se deriva MENSUAL por defecto. Revisa la configuración del producto.",
                    profile.getCreditAccountId(), profile.getProductType());
        }
        return profile.cadence();
    }

    private LocalDate fixedDayOfMonth(LocalDate alta, Integer day, int cycle) {
        if (day == null) return null;
        LocalDate first = alta.withDayOfMonth(Math.min(day, alta.lengthOfMonth()));
        if (!first.isAfter(alta)) {
            first = first.plusMonths(1);
            first = first.withDayOfMonth(Math.min(day, first.lengthOfMonth()));
        }
        LocalDate objetivo = first.plusMonths(cycle - 1L);
        return objetivo.withDayOfMonth(Math.min(day, objetivo.lengthOfMonth()));
    }

    /**
     * El calendario completo de un crédito a plazo, para materializarlo de una vez al darlo de alta.
     *
     * <p>Se genera entero y no ciclo a ciclo porque así el backoffice puede mostrar el calendario de
     * cortes desde el primer día, y porque la corrida diaria pasa a ser una consulta por fecha en vez
     * de un cálculo por cuenta.
     */
    public List<CutoffSchedule> deriveAll(AccountCloseProfile profile, ClosePolicy policy, int maxCycles) {
        List<CutoffSchedule> cutoffs = new ArrayList<>();
        for (int cycle = 1; cycle <= maxCycles; cycle++) {
            Optional<CutoffSchedule> c = derive(profile, policy, cycle);
            if (c.isEmpty()) break;
            cutoffs.add(c.get());
        }
        log.debug("Calendario de corte derivado cuenta={} regla={} cortes={}",
                profile.getCreditAccountId(), policy.cutoffRule(), cutoffs.size());
        return cutoffs;
    }
}

package com.fintech.closing.application.service;

import com.fintech.closing.domain.AccountCloseProfile;
import com.fintech.closing.domain.ClosePolicy;
import com.fintech.closing.domain.CutoffRule;
import com.fintech.closing.domain.CutoffSchedule;
import com.fintech.closing.domain.PaymentCadence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
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

    private final BusinessCalendarService calendario;

    public CutoffScheduleDeriver(BusinessCalendarService calendario) {
        this.calendario = calendario;
    }

    /**
     * El corte del ciclo {@code n} de una cuenta, ya corrido si cae en inhábil.
     *
     * @return vacío si el producto no tiene corte, o si el ciclo excede el plazo del crédito.
     */
    public Optional<CutoffSchedule> derive(AccountCloseProfile perfil, ClosePolicy politica, int ciclo) {
        if (politica.cutoffRule() == CutoffRule.NONE || perfil.getActivatedOn() == null || ciclo < 1) {
            return Optional.empty();
        }
        // Un préstamo a plazo no corta después de su última cuota: ahí ya no hay nada que cortar.
        if (perfil.getTermPeriods() != null && !perfil.isRevolving() && ciclo > perfil.getTermPeriods()) {
            return Optional.empty();
        }

        LocalDate crudo = fechaCruda(perfil, politica, ciclo);
        if (crudo == null) return Optional.empty();

        String calendarCode = politica.getCalendarCode();
        LocalDate corte = calendario.shift(calendarCode, crudo, politica.shift());
        LocalDate limite = calendario.shift(calendarCode,
                corte.plusDays(politica.getPaymentDueOffsetDays()), politica.shift());

        // El corrimiento del corte puede empujarlo más allá de la fecha límite calculada sobre el
        // crudo. La invariante `limite >= corte` la impone la base; se respeta aquí.
        if (limite.isBefore(corte)) limite = corte;

        return Optional.of(CutoffSchedule.scheduled(perfil.getCreditAccountId(), ciclo, corte, limite));
    }

    /** La fecha antes de aplicar el calendario. Aquí vive la diferencia entre reglas. */
    private LocalDate fechaCruda(AccountCloseProfile perfil, ClosePolicy politica, int ciclo) {
        LocalDate alta = perfil.getActivatedOn();

        return switch (politica.cutoffRule()) {
            // No revolvente: la cadencia del plan, anclada al alta. El primer corte cae un período
            // después de activar — igual que la primera cuota que genera cartera.
            case INSTALLMENT_DUE_DATE -> PaymentCadence.from(perfil.getPaymentFrequency()).advance(alta, ciclo);

            // Revolvente: ciclo mensual anclado al DÍA de la activación. Es lo que hace que dos
            // tarjetas activadas el 3 y el 20 corten en días distintos, que es como debe ser.
            case CYCLE_FROM_ACTIVATION -> alta.plusMonths(ciclo);

            // Día fijo del mes, igual para todo el producto. El primer corte es el siguiente que
            // llegue después del alta, no el del mes del alta si ya pasó.
            case DAY_OF_MONTH -> diaFijoDelMes(alta, politica.getCutoffDay(), ciclo);

            case NONE -> null;
        };
    }

    private LocalDate diaFijoDelMes(LocalDate alta, Short dia, int ciclo) {
        if (dia == null) return null;
        LocalDate primero = alta.withDayOfMonth(Math.min(dia, alta.lengthOfMonth()));
        if (!primero.isAfter(alta)) {
            primero = primero.plusMonths(1);
            primero = primero.withDayOfMonth(Math.min(dia, primero.lengthOfMonth()));
        }
        LocalDate objetivo = primero.plusMonths(ciclo - 1L);
        return objetivo.withDayOfMonth(Math.min(dia, objetivo.lengthOfMonth()));
    }

    /**
     * El calendario completo de un crédito a plazo, para materializarlo de una vez al darlo de alta.
     *
     * <p>Se genera entero y no ciclo a ciclo porque así el backoffice puede mostrar el calendario de
     * cortes desde el primer día, y porque la corrida diaria pasa a ser una consulta por fecha en vez
     * de un cálculo por cuenta.
     */
    public List<CutoffSchedule> deriveAll(AccountCloseProfile perfil, ClosePolicy politica, int maxCiclos) {
        List<CutoffSchedule> cortes = new ArrayList<>();
        for (int ciclo = 1; ciclo <= maxCiclos; ciclo++) {
            Optional<CutoffSchedule> c = derive(perfil, politica, ciclo);
            if (c.isEmpty()) break;
            cortes.add(c.get());
        }
        log.debug("Calendario de corte derivado cuenta={} regla={} cortes={}",
                perfil.getCreditAccountId(), politica.cutoffRule(), cortes.size());
        return cortes;
    }
}

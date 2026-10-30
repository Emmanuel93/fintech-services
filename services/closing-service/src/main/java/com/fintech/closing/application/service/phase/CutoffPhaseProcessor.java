package com.fintech.closing.application.service.phase;

import com.fintech.closing.application.port.in.CloseUnitProcessor;
import com.fintech.closing.application.port.out.AccountCloseProfileRepository;
import com.fintech.closing.application.port.out.ClosingEventPublisher;
import com.fintech.closing.application.port.out.CutoffScheduleRepository;
import com.fintech.closing.application.service.ClosePolicyResolver;
import com.fintech.closing.application.service.CloseRunWorker;
import com.fintech.closing.application.service.CutoffScheduleDeriver;
import com.fintech.closing.domain.AccountCloseProfile;
import com.fintech.closing.domain.ClosePhase;
import com.fintech.closing.domain.ClosePolicy;
import com.fintech.closing.domain.CloseUnit;
import com.fintech.closing.domain.CutoffSchedule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Optional;

/**
 * Sella el corte de una cuenta. Es la única fase donde el cierre hace <b>trabajo propio</b>, porque
 * el corte es suyo.
 *
 * <p><b>Qué sella y qué deliberadamente no.</b> Sella el saldo al corte y las fechas — lo que
 * conoce por su proyección — y para un revolvente calcula el <b>pago mínimo</b>, que es un número
 * que nace del corte y no existe en ningún otro lado.
 *
 * <p>Para un producto a plazo <b>no inventa el importe de la cuota</b>. Ese número sale del plan de
 * amortización, que es de cartera; duplicar aquí la aritmética crearía una segunda fuente de verdad
 * sobre el mismo importe y las dos divergirían al primer redondeo. El corte viaja con el saldo y
 * cartera completa el exigible desde su plan cuando lo proyecta de vuelta.
 */
@Component
public class CutoffPhaseProcessor implements CloseUnitProcessor {

    private static final Logger log = LoggerFactory.getLogger(CutoffPhaseProcessor.class);

    private final AccountCloseProfileRepository profiles;
    private final CutoffScheduleRepository cutoffs;
    private final ClosePolicyResolver policyResolver;
    private final CutoffScheduleDeriver deriver;
    private final ClosingEventPublisher publisher;

    public CutoffPhaseProcessor(AccountCloseProfileRepository profiles,
                                 CutoffScheduleRepository cutoffs,
                                 ClosePolicyResolver policyResolver,
                                 CutoffScheduleDeriver deriver,
                                 ClosingEventPublisher publisher) {
        this.profiles       = profiles;
        this.cutoffs        = cutoffs;
        this.policyResolver = policyResolver;
        this.deriver        = deriver;
        this.publisher      = publisher;
    }

    @Override
    public ClosePhase phase() { return ClosePhase.CUTOFF; }

    @Override
    public void process(CloseUnit unit, LocalDate businessDate) {
        AccountCloseProfile profile = profiles.findById(unit.getCreditAccountId())
                .orElseThrow(() -> new CloseRunWorker.UnitSkipped("cuenta sin proyección"));

        Optional<CutoffSchedule> hoy = cutoffs.findByAccount(profile.getCreditAccountId()).stream()
                .filter(c -> c.getCutoffDate().equals(businessDate) && "SCHEDULED".equals(c.getStatus()))
                .findFirst();
        if (hoy.isEmpty()) {
            // La inmensa mayoría de las cuentas no cortan hoy. No es un fallo.
            throw new CloseRunWorker.UnitSkipped("no le toca corte el " + businessDate);
        }

        CutoffSchedule cutoff = hoy.get();
        ClosePolicy policy = policyResolver.require(null, profile.getProductType(), businessDate);

        BigDecimal principal = profile.getPrincipalBalance();
        BigDecimal balance     = profile.getTotalDebt();
        BigDecimal accrued = balance.subtract(principal).max(BigDecimal.ZERO);

        BigDecimal amountDue = profile.isRevolving() ? balance : null;
        BigDecimal minimum   = profile.isRevolving()
                ? minimumPaymentFor(balance, policy, profile)
                : null;

        cutoff.seal(principal, accrued, BigDecimal.ZERO, amountDue, minimum, 0);
        cutoffs.save(cutoff);

        // Se agenda el ciclo siguiente si el calendario no lo tenía materializado — es lo que
        // mantiene vivo el ciclo de un revolvente, que no tiene plazo.
        int next = cutoff.getCycleNumber() + 1;
        if (cutoffs.find(profile.getCreditAccountId(), next).isEmpty()) {
            deriver.derive(profile, policy, next).ifPresent(cutoffs::save);
        }
        cutoffs.find(profile.getCreditAccountId(), next)
                .ifPresent(c -> profile.scheduleNextCutoff(next, c.getCutoffDate()));
        profiles.save(profile);

        publisher.publishCutoffClosed(cutoff, profile.getObligorPartyId(), profile.getProductType());

        log.info("Corte sellado cuenta={} ciclo={} fecha={} saldo={} minimo={}",
                profile.getCreditAccountId(), cutoff.getCycleNumber(), businessDate, balance, minimum);
    }
    /**
     * El pago mínimo del ciclo, <b>con el porcentaje que configuró el producto</b>.
     *
     * <h2>Lo que esto arregla</h2>
     *
     * Era una constante de clase del 5 %: el mismo mínimo para una tarjeta de nómina y una de
     * consumo, y cambiarlo para un producto exigía un despliegue. Ahora sale de la política, que ya
     * era por producto, versionada y con vigencia — sólo le faltaba la columna.
     *
     * <h2>Y qué pasa si el producto no lo declara</h2>
     *
     * Un revolvente <b>sin</b> porcentaje configurado no exige mínimo, y eso es un hecho del
     * producto, no un fallo: se devuelve nulo y el corte se sella sin mínimo. Pero se registra en
     * WARN, porque un revolvente sin pago mínimo es raro y lo más probable es que falte la
     * configuración — y un estado de cuenta que no exige nada es difícil de notar mirando importes.
     */
    private BigDecimal minimumPaymentFor(BigDecimal balanceAtCutoff, ClosePolicy policy,
                                         AccountCloseProfile profile) {
        return policy.minimumPaymentRate()
                .map(rate -> balanceAtCutoff.multiply(rate).setScale(2, RoundingMode.HALF_UP))
                .orElseGet(() -> {
                    log.warn("El producto {} es revolvente y su política ({}) no declara porcentaje "
                             + "de pago mínimo: el corte de la cuenta {} se sella SIN mínimo exigible.",
                            profile.getProductType(), policy.getPolicyId(), profile.getCreditAccountId());
                    return null;
                });
    }

}

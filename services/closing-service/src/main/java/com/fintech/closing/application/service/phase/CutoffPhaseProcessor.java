package com.fintech.closing.application.service.phase;

import com.fintech.closing.application.ClosingProperties;
import com.fintech.closing.application.port.in.CloseUnitProcessor;
import com.fintech.closing.application.port.out.AccountCloseProfileRepository;
import com.fintech.closing.application.port.out.BalanceMovementRepository;
import com.fintech.closing.application.port.out.ClosingEventPublisher;
import com.fintech.closing.application.port.out.CutoffScheduleRepository;
import com.fintech.closing.application.port.out.PortfolioDaySnapshots;
import com.fintech.closing.application.port.out.PortfolioDaySnapshots.AccountSnapshot;
import com.fintech.closing.application.service.ClosePolicyResolver;
import com.fintech.closing.application.service.CloseRunWorker;
import com.fintech.closing.application.service.CutoffScheduleDeriver;
import com.fintech.closing.domain.AccountCloseProfile;
import com.fintech.closing.domain.BillingCycle;
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
import java.util.List;
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
    private final BalanceMovementRepository movements;
    private final CutoffScheduleRepository cutoffs;
    private final ClosePolicyResolver policyResolver;
    private final CutoffScheduleDeriver deriver;
    private final ClosingEventPublisher publisher;
    private final ClosingProperties properties;
    private final PortfolioDaySnapshots snapshots;

    public CutoffPhaseProcessor(AccountCloseProfileRepository profiles,
                                 CutoffScheduleRepository cutoffs,
                                 ClosePolicyResolver policyResolver,
                                 CutoffScheduleDeriver deriver,
                                 ClosingEventPublisher publisher,
                                 ClosingProperties properties,
                                 BalanceMovementRepository movements,
                                 PortfolioDaySnapshots snapshots) {
        this.snapshots      = snapshots;
        this.profiles       = profiles;
        this.cutoffs        = cutoffs;
        this.policyResolver = policyResolver;
        this.deriver        = deriver;
        this.publisher      = publisher;
        this.properties     = properties;
        this.movements      = movements;
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

        // El saldo al cierre de D, no el de ahora: lo que llegó con fecha posterior —un pago de las 00:07
        // de D+1— se resta. El corte da lo mismo corra a las 00:06 o a las 03:00.
        BalanceMovementRepository.Later despues = movements.after(profile.getCreditAccountId(), businessDate);
        BigDecimal principal = profile.getPrincipalBalance().subtract(despues.principal());
        BigDecimal balance     = profile.getTotalDebt().subtract(despues.debt());
        BigDecimal accrued = balance.subtract(principal).max(BigDecimal.ZERO);

        // The portfolio's snapshot of D is the other half of the audit trail: its days past due are what
        // the statement prints, and its principal must agree with the rewind above. A mismatch means the
        // two services drifted apart on this account; the unit fails and the account shows up in trouble.
        if (!unit.hasSnapshot()) {
            AccountSnapshot foto = snapshots.lookup(businessDate, List.of(unit.getCreditAccountId())).stream()
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("cartera no tiene foto del " + businessDate
                            + " para la cuenta " + unit.getCreditAccountId() + ": el corte no puede sellar sin ella"));
            unit.attachSnapshot(foto.daysDelinquent(), foto.principal(), foto.takenAt());
        }
        if (unit.getPrincipalAtDay() != null && unit.getPrincipalAtDay().compareTo(principal) != 0) {
            throw new IllegalStateException("el capital al cierre del " + businessDate + " no coincide con la foto de cartera: "
                    + "closing calcula " + principal + " y la foto dice " + unit.getPrincipalAtDay());
        }
        int daysDelinquent = unit.getDaysDelinquentAtDay();

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

        // El período que este corte cierra empieza el día siguiente al corte anterior; el primero,
        // el día de la activación. Es lo que el estado de cuenta pinta como «del … al …».
        LocalDate periodStart = cutoffs.find(profile.getCreditAccountId(), cutoff.getCycleNumber() - 1)
                .map(prev -> prev.getCutoffDate().plusDays(1))
                .orElse(profile.getActivatedOn() != null ? profile.getActivatedOn() : businessDate);
        String holderKind = unit.getHolderKind() != null
                ? unit.getHolderKind()
                : properties.getCutoff().holderKindOf(profile.getProductType());

        publisher.publishCutoffClosed(cutoff, profile, unit.getRunId(), periodStart, holderKind, daysDelinquent);

        log.info("Corte sellado cuenta={} ciclo={} fecha={} saldo={} minimo={} atraso={}",
                profile.getCreditAccountId(), cutoff.getCycleNumber(), businessDate, balance, minimum, daysDelinquent);
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
        // The product's cycle pinned to the account wins; the policy is the fallback for older accounts. A cycle that
        // declares no minimum means it: a distributor line's statement asks for its fortnight's installments, not a
        // share of the balance.
        if (profile.billingCycle().isPresent() && profile.billingCycle().get().minimumPayment().isEmpty()) {
            return null;
        }
        return profile.billingCycle().flatMap(BillingCycle::minimumPayment)
                .or(policy::minimumPaymentRate)
                .map(rate -> balanceAtCutoff.multiply(rate).setScale(2, RoundingMode.HALF_UP))
                .orElseGet(() -> {
                    log.warn("El producto {} es revolvente y su política ({}) no declara porcentaje "
                             + "de pago mínimo: el corte de la cuenta {} se sella SIN mínimo exigible.",
                            profile.getProductType(), policy.getPolicyId(), profile.getCreditAccountId());
                    return null;
                });
    }

}

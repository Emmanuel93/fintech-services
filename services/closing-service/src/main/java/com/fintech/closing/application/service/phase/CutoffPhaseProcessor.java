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

    /** Porcentaje del saldo exigible como pago mínimo de un revolvente. */
    private static final BigDecimal PAGO_MINIMO_PCT = new BigDecimal("0.05");

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
        AccountCloseProfile perfil = profiles.findById(unit.getCreditAccountId())
                .orElseThrow(() -> new CloseRunWorker.UnitSkipped("cuenta sin proyección"));

        Optional<CutoffSchedule> hoy = cutoffs.findByAccount(perfil.getCreditAccountId()).stream()
                .filter(c -> c.getCutoffDate().equals(businessDate) && "SCHEDULED".equals(c.getStatus()))
                .findFirst();
        if (hoy.isEmpty()) {
            // La inmensa mayoría de las cuentas no cortan hoy. No es un fallo.
            throw new CloseRunWorker.UnitSkipped("no le toca corte el " + businessDate);
        }

        CutoffSchedule corte = hoy.get();
        ClosePolicy politica = policyResolver.require(null, perfil.getProductType(), businessDate);

        BigDecimal principal = perfil.getPrincipalBalance();
        BigDecimal saldo     = perfil.getTotalDebt();
        BigDecimal devengado = saldo.subtract(principal).max(BigDecimal.ZERO);

        BigDecimal exigible = perfil.isRevolving() ? saldo : null;
        BigDecimal minimo   = perfil.isRevolving()
                ? saldo.multiply(PAGO_MINIMO_PCT).setScale(2, RoundingMode.HALF_UP)
                : null;

        corte.seal(principal, devengado, BigDecimal.ZERO, exigible, minimo, 0);
        cutoffs.save(corte);

        // Se agenda el ciclo siguiente si el calendario no lo tenía materializado — es lo que
        // mantiene vivo el ciclo de un revolvente, que no tiene plazo.
        int siguiente = corte.getCycleNumber() + 1;
        if (cutoffs.find(perfil.getCreditAccountId(), siguiente).isEmpty()) {
            deriver.derive(perfil, politica, siguiente).ifPresent(cutoffs::save);
        }
        cutoffs.find(perfil.getCreditAccountId(), siguiente)
                .ifPresent(c -> perfil.scheduleNextCutoff(siguiente, c.getCutoffDate()));
        profiles.save(perfil);

        publisher.publishCutoffClosed(corte, perfil.getObligorPartyId(), perfil.getProductType());

        log.info("Corte sellado cuenta={} ciclo={} fecha={} saldo={} minimo={}",
                perfil.getCreditAccountId(), corte.getCycleNumber(), businessDate, saldo, minimo);
    }
}

package com.fintech.closing.application.service.phase;

import com.fintech.closing.application.port.in.CloseUnitProcessor;
import com.fintech.closing.application.port.out.AccountCloseProfileRepository;
import com.fintech.closing.application.port.out.ClosingEventPublisher;
import com.fintech.closing.application.service.CloseRunWorker;
import com.fintech.closing.domain.ClosePhase;
import com.fintech.closing.domain.CloseUnit;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Abre la ventana de devengo de una cuenta.
 *
 * <p><b>El cierre no devenga.</b> Publica el hecho de que la ventana está abierta y
 * {@code charges} —dueño del cálculo— reacciona con su propia lógica y su propia idempotencia.
 * Meter aquí la aritmética del interés crearía una segunda fuente de verdad sobre el mismo número,
 * y las dos divergirían al primer redondeo.
 */
@Component
public class AccrualPhaseProcessor implements CloseUnitProcessor {

    private final AccountCloseProfileRepository profiles;
    private final ClosingEventPublisher publisher;

    public AccrualPhaseProcessor(AccountCloseProfileRepository profiles, ClosingEventPublisher publisher) {
        this.profiles  = profiles;
        this.publisher = publisher;
    }

    @Override
    public ClosePhase phase() { return ClosePhase.ACCRUAL; }

    @Override
    public void process(CloseUnit unit, LocalDate businessDate) {
        var perfil = profiles.findById(unit.getCreditAccountId())
                .orElseThrow(() -> new CloseRunWorker.UnitSkipped("cuenta sin proyección"));

        // Una cuenta liquidada o castigada no devenga. No es un fallo: es que no aplica.
        if (perfil.isTerminal()) {
            throw new CloseRunWorker.UnitSkipped("cuenta en estado terminal: " + perfil.getStatus());
        }
        BigDecimal saldo = perfil.getPrincipalBalance();
        if (saldo == null || saldo.signum() <= 0) {
            throw new CloseRunWorker.UnitSkipped("sin saldo que devengar");
        }

        // Con qué versión de saldo se cerró esta unidad. Es lo que permite después decir si un
        // evento llegó tarde en vez de tener que adivinarlo.
        unit.setBalanceVersion(perfil.getLastBalanceVersion());

        publisher.publishUnitWindowOpened(unit.getRunId(), unit.getCreditAccountId(),
                ClosePhase.ACCRUAL, businessDate, perfil.getProductType());
    }
}

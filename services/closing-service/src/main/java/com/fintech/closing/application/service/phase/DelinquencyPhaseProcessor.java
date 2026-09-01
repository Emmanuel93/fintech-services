package com.fintech.closing.application.service.phase;

import com.fintech.closing.application.port.in.CloseUnitProcessor;
import com.fintech.closing.application.port.out.AccountCloseProfileRepository;
import com.fintech.closing.application.port.out.ClosingEventPublisher;
import com.fintech.closing.application.service.CloseRunWorker;
import com.fintech.closing.domain.ClosePhase;
import com.fintech.closing.domain.CloseUnit;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * Abre la ventana del cálculo de mora.
 *
 * <p>Sólo corre con el devengo del día ya sellado; la barrera la impone el planificador. Hoy esa
 * garantía es que la mora arranca 59 minutos después del devengo y se espera que alcance — si no
 * alcanza, se calcula sobre datos viejos y nada lo detecta.
 */
@Component
public class DelinquencyPhaseProcessor implements CloseUnitProcessor {

    private final AccountCloseProfileRepository profiles;
    private final ClosingEventPublisher publisher;

    public DelinquencyPhaseProcessor(AccountCloseProfileRepository profiles, ClosingEventPublisher publisher) {
        this.profiles  = profiles;
        this.publisher = publisher;
    }

    @Override
    public ClosePhase phase() { return ClosePhase.DELINQUENCY; }

    @Override
    public void process(CloseUnit unit, LocalDate businessDate) {
        var perfil = profiles.findById(unit.getCreditAccountId())
                .orElseThrow(() -> new CloseRunWorker.UnitSkipped("cuenta sin proyección"));

        if (perfil.isTerminal()) {
            throw new CloseRunWorker.UnitSkipped("cuenta en estado terminal: " + perfil.getStatus());
        }

        publisher.publishUnitWindowOpened(unit.getRunId(), unit.getCreditAccountId(),
                ClosePhase.DELINQUENCY, businessDate, perfil.getProductType());
    }
}

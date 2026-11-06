package com.fintech.closing.infrastructure.adapter.in.messaging;

import com.fintech.closing.application.port.out.PortfolioDayCloseLog;
import com.fintech.closing.application.service.cutoff.CutoffRunService;
import com.fintech.closing.application.service.cutoff.CutoffRunService.CutoffNotDue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * El disparo normal del corte: cartera avisa que cerró el día D —el devengo de D y la mora al D+1 ya
 * están— y closing corta D. Es el último eslabón del cierre que empieza a las 00:05 en cargos.
 *
 * <p>Anota el día y avanza: corta, en orden, cada día que cartera cerró y closing no, si closing ya
 * consumió los saldos que cartera publicó antes del aviso. Si no, el día queda esperando y lo retoma la
 * reanudación. Un aviso repetido no corta dos veces.
 */
@Component
@ConditionalOnProperty(name = "fintech.closing.cutoff.trigger-on-day-closed", havingValue = "true", matchIfMissing = true)
public class DayClosedListener {

    private static final Logger log = LoggerFactory.getLogger(DayClosedListener.class);

    private final CutoffRunService cutoffs;
    private final PortfolioDayCloseLog dayCloses;

    public DayClosedListener(CutoffRunService cutoffs, PortfolioDayCloseLog dayCloses) {
        this.cutoffs   = cutoffs;
        this.dayCloses = dayCloses;
    }

    @KafkaListener(topics = "credit-portfolio.day-closed", containerFactory = "dayClosedListenerContainerFactory")
    public void onDayClosed(DayClosedMessage message) {
        if (message == null || message.businessDate() == null) return;
        dayCloses.record(message.businessDate(), message.delinquencyAsOf(), message.accounts(), message.errors(),
                message.heldAccountsOrEmpty());
        try {
            for (var corte : cutoffs.advance("credit-portfolio.day-closed")) {
                log.info("Cartera cerró el {} (mora al {}): corte del {} con {} cuentas, estado {}",
                        message.businessDate(), message.delinquencyAsOf(), corte.businessDate(),
                        corte.plannedUnits(), corte.status());
            }
        } catch (CutoffNotDue e) {
            // Un aviso de un día que no ha terminado no lo corta nadie: es un error de quien lo mandó.
            log.warn("Aviso de cierre del {} ignorado: {}", message.businessDate(), e.getMessage());
        }
    }
}

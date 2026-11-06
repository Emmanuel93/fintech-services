package com.fintech.charges.infrastructure.adapter.in.messaging;

import com.fintech.charges.application.port.out.AccrualDayRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Cartera cerró el día D. Es la compuerta del devengo de D+1 —su moratorio se cobra sobre la mora que
 * midió ese cierre— y la confirmación de que el aviso de cargos de D llegó.
 *
 * <p>Sólo lo anota. El job del cierre, que puede estar esperando esta fila, la ve en su siguiente
 * consulta.
 */
@Component
class PortfolioDayClosedListener {

    private static final Logger log = LoggerFactory.getLogger(PortfolioDayClosedListener.class);

    private final AccrualDayRepository days;

    PortfolioDayClosedListener(AccrualDayRepository days) {
        this.days = days;
    }

    @KafkaListener(
            topics = "credit-portfolio.day-closed",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "portfolioDayClosedListenerContainerFactory")
    void onDayClosed(PortfolioDayClosedPayload p) {
        if (p == null || p.businessDate() == null) return;
        days.recordPortfolioClose(p.businessDate(), p.delinquencyAsOf());
        log.info("Cartera cerró el {} (mora al {}): el devengo del {} puede seguir", p.businessDate(),
                p.delinquencyAsOf(), p.businessDate().plusDays(1));
    }
}

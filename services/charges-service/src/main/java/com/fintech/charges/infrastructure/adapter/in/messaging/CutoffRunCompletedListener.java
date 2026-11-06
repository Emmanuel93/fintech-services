package com.fintech.charges.infrastructure.adapter.in.messaging;

import com.fintech.charges.application.port.out.AccrualDayRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Closing terminó el corte del día D. Es la última compuerta del devengo de D+1: con ella la cadena
 * queda en serie de punta a punta. Un corte con fallas también terminó —sus cuentas se reintentan a
 * mano y se sellan con el saldo al cierre de D—, así que no detiene el día siguiente.
 */
@Component
class CutoffRunCompletedListener {

    private static final Logger log = LoggerFactory.getLogger(CutoffRunCompletedListener.class);

    private final AccrualDayRepository days;

    CutoffRunCompletedListener(AccrualDayRepository days) {
        this.days = days;
    }

    @KafkaListener(
            topics = "closing.cutoff-run-completed",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "cutoffRunCompletedListenerContainerFactory")
    void onCutoffRunCompleted(CutoffRunCompletedPayload p) {
        if (p == null || p.businessDate() == null) return;
        days.recordCutoffCompleted(p.businessDate(), p.status());
        if ("SEALED".equals(p.status())) {
            log.info("Closing terminó el corte del {}: el devengo del {} puede seguir", p.businessDate(),
                    p.businessDate().plusDays(1));
        } else {
            log.warn("El corte del {} terminó {}: tiene cuentas que revisar; el devengo del {} sigue", p.businessDate(),
                    p.status(), p.businessDate().plusDays(1));
        }
    }
}

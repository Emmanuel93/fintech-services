package com.fintech.banking.infrastructure.adapter.out.messaging;

import com.fintech.banking.application.service.ReconciliationAlertPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Publica el descuadre en {@code banking.reconciliation-alert}.
 *
 * <p>El cierre lo consume para no sellar el día, y operación para investigarlo. Una alerta sin
 * consumidor sería exactamente lo que este trabajo vino a corregir.
 */
@Component
class KafkaReconciliationAlertPublisher implements ReconciliationAlertPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaReconciliationAlertPublisher.class);
    static final String TOPIC = "banking.reconciliation-alert";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    KafkaReconciliationAlertPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public void publicarDescuadre(UUID bankAccountId, LocalDate businessDate,
                                  BigDecimal saldoContable, BigDecimal saldoDelBanco,
                                  BigDecimal partidasEnConciliacion, BigDecimal diferencia,
                                  int partidasAbiertas) {
        var payload = new ReconciliationAlertPayload(UUID.randomUUID().toString(), bankAccountId,
                businessDate, "LEDGER_VS_BANK", saldoContable, saldoDelBanco,
                partidasEnConciliacion, diferencia, partidasAbiertas, Instant.now());

        kafkaTemplate.send(TOPIC, bankAccountId.toString(), payload)
                .whenComplete((r, ex) -> {
                    if (ex != null) {
                        log.error("No se pudo publicar la alerta de descuadre cuenta={} fecha={}: {}",
                                bankAccountId, businessDate, ex.getMessage());
                    }
                });
    }

    /** El hallazgo lleva las tres cifras, no sólo la diferencia: sin ellas nadie puede reproducirlo. */
    record ReconciliationAlertPayload(String eventId, UUID bankAccountId, LocalDate businessDate,
                                      String checkType, BigDecimal ledgerBalance,
                                      BigDecimal bankBalance, BigDecimal suspenseTotal,
                                      BigDecimal difference, int openSuspenseEntries,
                                      Instant occurredOn) {}
}

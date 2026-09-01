package com.fintech.banking.infrastructure.adapter.in.messaging;

import com.fintech.banking.application.port.out.BankAccountRepository;
import com.fintech.banking.application.service.BankReconciliationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * BK-37 · un abono <b>recibido</b>, tal como STP lo reporta en su conciliación de entradas.
 *
 * <p>La bifurcación que AN-06 tenía que resolver quedó del lado barato: el contrato de STP ya expone
 * {@code tipoOrden="R"}, así que la ingesta <b>extiende el poller</b> que ya existía en vez de
 * construir un ingestor de archivo.
 *
 * <p>La cuenta se resuelve por la <b>CLABE beneficiaria</b>, que en un abono recibido es la nuestra.
 * Un abono a una CLABE que no está dada de alta no se ingiere: sería inventar de qué cuenta es.
 */
@Component
public class CreditReceivedListener {

    private static final Logger log = LoggerFactory.getLogger(CreditReceivedListener.class);

    private final BankReconciliationService conciliacion;
    private final BankAccountRepository cuentas;

    public CreditReceivedListener(BankReconciliationService conciliacion,
                                  BankAccountRepository cuentas) {
        this.conciliacion = conciliacion;
        this.cuentas      = cuentas;
    }

    @KafkaListener(
            topics = "stp.credit-received",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "creditReceivedListenerContainerFactory")
    public void onCreditReceived(CreditReceivedPayload event) {
        cuentas.findByClabe(event.beneficiaryAccount()).ifPresentOrElse(
                cuenta -> conciliacion.ingerir(cuenta.getId(), event.businessDate(), "CREDIT",
                        event.amount(), event.externalId(), event.trackingKey(),
                        event.concept(), event.senderName(), event.senderAccount()),
                () -> log.warn("Abono recibido a una CLABE no dada de alta ****{} — no se ingiere",
                        sufijo(event.beneficiaryAccount())));
    }

    /** Nunca se registra la CLABE completa, ni siquiera la de un tercero. */
    private static String sufijo(String clabe) {
        return clabe == null || clabe.length() < 4 ? "?" : clabe.substring(clabe.length() - 4);
    }

    public record CreditReceivedPayload(String eventId,
                                 String externalId,
                                 String trackingKey,
                                 BigDecimal amount,
                                 LocalDate businessDate,
                                 String beneficiaryAccount,
                                 String senderName,
                                 String senderAccount,
                                 String concept) {}
}

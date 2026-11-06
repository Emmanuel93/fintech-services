package com.fintech.charges.infrastructure.adapter.out.messaging;

import com.fintech.charges.application.port.out.ChargeEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class KafkaChargesEventPublisher implements ChargeEventPublisher {

    static final String TOPIC_CHARGE_APPLIED  = "charges.charge-applied";
    static final String TOPIC_CHARGE_REVERSED = "charges.charge-reversed";
    public static final String TOPIC_DAY_ACCRUED = "charges.day-accrued";
    public static final String TOPIC_ACCRUAL_DUE = "charges.accrual-due";
    public static final String TOPIC_ACCOUNT_ACCRUED = "charges.account-accrued";

    private static final Logger log = LoggerFactory.getLogger(KafkaChargesEventPublisher.class);

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public KafkaChargesEventPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public void publishChargeApplied(String eventId, UUID creditAccountId,
                                      String chargeType, BigDecimal totalAmount,
                                      java.time.LocalDate effectiveDate, java.time.LocalDate dayCloseDate) {
        var payload = new ChargeAppliedOutboundPayload(eventId, creditAccountId, chargeType, totalAmount,
                effectiveDate, dayCloseDate);
        // Se espera al broker. Antes se mandaba y se olvidaba: si el envío fallaba, el cargo quedaba
        // devengado aquí y nunca llegaba a cartera —interés que el estado de cuenta no mostraba—. Ahora
        // la excepción deshace la transacción del cargo y el calendario se reintenta.
        await(kafkaTemplate.send(TOPIC_CHARGE_APPLIED, creditAccountId.toString(), payload),
                "el cargo " + eventId + " (" + chargeType + ")");
        log.debug("charge-applied published chargeId={} type={} amount={}", eventId, chargeType, totalAmount);
    }

    @Override
    public void publishChargeReversed(String eventId, UUID creditAccountId,
                                       String originalChargeType, BigDecimal amount, boolean waived) {
        var payload = new ChargeReversedOutboundPayload(eventId, creditAccountId,
                originalChargeType, amount, waived);
        kafkaTemplate.send(TOPIC_CHARGE_REVERSED, creditAccountId.toString(), payload)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("charge-reversed publish failed chargeId={} waived={}: {}",
                                eventId, waived, ex.getMessage());
                    } else {
                        log.debug("charge-reversed published chargeId={} waived={}", eventId, waived);
                    }
                });
    }

    @Override
    public void publishDayAccrued(DayAccrued day) {
        var payload = new DayAccruedOutboundPayload(UUID.randomUUID().toString(), day.businessDate(),
                day.ordinarySchedules(), day.ordinaryErrors(), day.moratoriumSchedules(), day.moratoriumErrors(),
                day.ordinaryCharges(), day.ordinaryAmount(), day.moratoriumCharges(), day.moratoriumAmount(),
                day.elapsedMs(), day.completedAt(), day.accounts(), day.heldAccounts());
        // Todo lo enviado antes queda confirmado antes del aviso: cartera toma su foto de offsets al
        // recibirlo, y esa foto tiene que incluir hasta el último cargo del día.
        kafkaTemplate.flush();
        await(kafkaTemplate.send(TOPIC_DAY_ACCRUED, day.businessDate().toString(), payload),
                "el cierre de cargos del " + day.businessDate());
    }

    @Override
    public java.util.concurrent.CompletableFuture<?> publishAccrualDue(java.time.LocalDate businessDate,
                                                                       UUID creditAccountId) {
        return kafkaTemplate.send(TOPIC_ACCRUAL_DUE, creditAccountId.toString(),
                new AccrualDueOutboundPayload(businessDate, creditAccountId));
    }

    @Override
    public void publishAccountAccrued(AccountAccrued a) {
        var payload = new AccountAccruedOutboundPayload(UUID.randomUUID().toString(), a.businessDate(),
                a.creditAccountId(), a.ordinaryCharges(), a.ordinaryAmount(), a.moratoriumCharges(),
                a.moratoriumAmount());
        // Como el aviso del día: sus cargos, confirmados antes. Cartera toma su foto de offsets al recibirlo.
        kafkaTemplate.flush();
        await(kafkaTemplate.send(TOPIC_ACCOUNT_ACCRUED, a.creditAccountId().toString(), payload),
                "el devengo tardío de la cuenta " + a.creditAccountId() + " del " + a.businessDate());
    }

    private static void await(java.util.concurrent.CompletableFuture<?> envio, String que) {
        try {
            envio.get(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrumpido al publicar " + que, e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("No se pudo publicar " + que, e);
        }
    }
}

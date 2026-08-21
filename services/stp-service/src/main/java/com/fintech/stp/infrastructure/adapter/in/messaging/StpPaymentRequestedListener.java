package com.fintech.stp.infrastructure.adapter.in.messaging;

import com.fintech.stp.application.RegisterPaymentOrderCommand;
import com.fintech.stp.application.port.in.RegisterPaymentOrderUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Entrada del servicio. El topic es configurable ({@code fintech.stp.inbound-topic}) para que un
 * comprador pueda apuntarlo a su propia nomenclatura sin tocar código.
 */
@Component
public class StpPaymentRequestedListener {

    private static final Logger log = LoggerFactory.getLogger(StpPaymentRequestedListener.class);

    private final RegisterPaymentOrderUseCase registerPaymentOrder;

    public StpPaymentRequestedListener(RegisterPaymentOrderUseCase registerPaymentOrder) {
        this.registerPaymentOrder = registerPaymentOrder;
    }

    @KafkaListener(
            topics = "${fintech.stp.inbound-topic}",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "stpPaymentRequestedListenerContainerFactory")
    public void onPaymentRequested(StpPaymentRequestedPayload event) {
        log.debug("stp payment requested paymentRequestId={} companyId={} amount={}",
                event.paymentRequestId(), event.companyId(), event.amount());

        registerPaymentOrder.register(new RegisterPaymentOrderCommand(
                event.paymentRequestId(), event.companyId(), event.amount(), event.currency(),
                event.beneficiaryName(), event.beneficiaryAccount(), event.beneficiaryAccountType(),
                event.beneficiaryTaxId(), event.beneficiaryInstitution(), event.concept(),
                event.numericReference(), event.paymentType(), event.correlationId()));
    }
}

package com.fintech.creditportfolio.infrastructure.adapter.in.messaging;

import com.fintech.creditportfolio.application.CreateCreditAccountCommand;
import com.fintech.creditportfolio.application.port.in.ActivateCreditAccountUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class CreditProductCreationRequestedListener {

    private static final Logger log = LoggerFactory.getLogger(CreditProductCreationRequestedListener.class);

    private final ActivateCreditAccountUseCase activateUseCase;

    public CreditProductCreationRequestedListener(ActivateCreditAccountUseCase activateUseCase) {
        this.activateUseCase = activateUseCase;
    }

    @KafkaListener(
            topics = "origination.credit-product-creation-requested",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "creditProductCreationListenerContainerFactory")
    public void onMessage(CreditProductCreationRequestedPayload payload) {
        log.info("CreditProductCreationRequested received applicationId={} productCode={} behavior={}",
                payload.applicationId(), payload.productCode(), payload.productBehavior());

        activateUseCase.activate(new CreateCreditAccountCommand(
                payload.applicationId(),
                payload.contractNumber(),
                payload.obligorPartyId(),
                payload.productCode(),
                payload.productVersion(),
                payload.productType(),
                payload.productBehavior(),
                payload.approvedAmount(),
                payload.approvedLine(),
                payload.assignedTerm(),
                payload.nominalRate(),
                payload.moratoriumRate(),
                payload.amortizationType(),
                payload.openingFeeRate(),
                payload.clabeAccount(),
                payload.riskTier(),
                payload.promoterCode(),
                null,   // originUnitCode: origination no resuelve todavía la sucursal (la rellena
                        // la reconciliación del backoffice contra sales-org)
                null,   // vatRate: sin sucursal resuelta no hay zona que consultar, así que manda el
                        // nacional. En cuanto origination resuelva la sucursal, aquí se pide su tasa
                        // a sales-org y el crédito nace con la de su plaza.
                payload.obligorName(),
                payload.obligorTaxId(),
                // Lo que el cliente pidió al firmar, no el tope del producto. Nulo es "nadie lo
                // pidió" y es lo que deja el plan empezando en el período siguiente; el tope lo
                // aplica `OpcionesDePago`, recortando lo pedido si se pasa.
                payload.bnplDeferralDays()));
    }
}

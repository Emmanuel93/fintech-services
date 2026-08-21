package com.fintech.disbursement.infrastructure.adapter.in.messaging;

import com.fintech.disbursement.application.RequestDisbursementCommand;
import com.fintech.disbursement.application.port.in.RequestDisbursementUseCase;
import com.fintech.disbursement.domain.DisbursementSource;
import com.fintech.disbursement.domain.Rail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ACL — traduce "se activó un crédito" a "hay que pagar esto".
 *
 * <p><strong>Este archivo es la frontera.</strong> Todo el vocabulario de crédito muere aquí: lo que
 * sale hacia el núcleo es una orden de pago con la procedencia en campos opacos. Borrar este archivo
 * y sus dos hermanos deja un servicio de payouts que funciona igual por su API REST — que es la
 * definición operativa de "comercializable aparte" (§7.5, simulacro de extracción).
 */
@Component
public class CreditAccountActivatedListener {

    private static final Logger log = LoggerFactory.getLogger(CreditAccountActivatedListener.class);
    private static final String SOURCE_SYSTEM = "credit-portfolio";

    private final RequestDisbursementUseCase requestDisbursement;

    public CreditAccountActivatedListener(RequestDisbursementUseCase requestDisbursement) {
        this.requestDisbursement = requestDisbursement;
    }

    @KafkaListener(
            topics = "${fintech.disbursement.topics.inbound.credit-account-activated}",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "creditAccountActivatedListenerContainerFactory")
    public void onCreditAccountActivated(CreditAccountActivatedPayload event) {
        var instruction = event.disbursementInstruction();

        // Nueve servicios consumen este hecho. Los que no tienen nada que pagar, no pagan.
        if (instruction == null || instruction.amount() == null || instruction.amount().signum() <= 0) {
            log.debug("credit-account-activated sin instrucción de desembolso creditAccountId={}",
                    event.creditAccountId());
            return;
        }

        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("dispositionId", String.valueOf(instruction.dispositionId()));
        metadata.put("creditAccountId", String.valueOf(event.creditAccountId()));
        if (instruction.dispositionType() != null) {
            metadata.put("dispositionType", instruction.dispositionType());
        }

        requestDisbursement.request(new RequestDisbursementCommand(
                SOURCE_SYSTEM,
                DisbursementSource.DISPOSITION,
                String.valueOf(instruction.dispositionId()),
                // Idempotencia: el mismo hecho reentregado trae la misma disposición.
                String.valueOf(instruction.dispositionId()),
                null,
                instruction.companyId(),
                metadata,
                instruction.beneficiaryName(),
                instruction.beneficiaryAccount(),
                instruction.beneficiaryAccountType(),
                instruction.beneficiaryTaxId(),
                instruction.beneficiaryInstitution(),
                instruction.amount(),
                instruction.currency(),
                instruction.concept(),
                instruction.numericReference(),
                Rail.SPEI,
                String.valueOf(event.creditAccountId())));
    }
}

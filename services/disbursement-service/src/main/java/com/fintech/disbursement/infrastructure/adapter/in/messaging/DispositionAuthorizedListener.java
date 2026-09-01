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

/** ACL — disposición autorizada sobre una línea ya activa (camino de wallet). */
@Component
public class DispositionAuthorizedListener {

    private static final Logger log = LoggerFactory.getLogger(DispositionAuthorizedListener.class);
    private static final String SOURCE_SYSTEM = "credit-portfolio";

    private final RequestDisbursementUseCase requestDisbursement;

    public DispositionAuthorizedListener(RequestDisbursementUseCase requestDisbursement) {
        this.requestDisbursement = requestDisbursement;
    }

    @KafkaListener(
            topics = "${fintech.disbursement.topics.inbound.disposition-authorized}",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "dispositionAuthorizedListenerContainerFactory")
    public void onDispositionAuthorized(DispositionAuthorizedPayload event) {
        if (event.amount() == null || event.amount().signum() <= 0) {
            log.debug("disposition-authorized sin monto dispositionId={}", event.dispositionId());
            return;
        }

        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("dispositionId", String.valueOf(event.dispositionId()));
        metadata.put("creditAccountId", String.valueOf(event.creditAccountId()));
        if (event.dispositionType() != null) {
            metadata.put("dispositionType", event.dispositionType());
        }

        requestDisbursement.request(new RequestDisbursementCommand(
                SOURCE_SYSTEM,
                DisbursementSource.DISPOSITION,
                String.valueOf(event.dispositionId()),
                String.valueOf(event.dispositionId()),
                event.sourceCompanyKey(),
                event.companyId(),
                metadata,
                event.beneficiaryName(),
                event.beneficiaryAccount(),
                event.beneficiaryAccountType(),
                event.beneficiaryTaxId(),
                event.beneficiaryInstitution(),
                event.amount(),
                event.currency(),
                event.concept(),
                event.numericReference(),
                Rail.SPEI,
                String.valueOf(event.creditAccountId())));
    }
}

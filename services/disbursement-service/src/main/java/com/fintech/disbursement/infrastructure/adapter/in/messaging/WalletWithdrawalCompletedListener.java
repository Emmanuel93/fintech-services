package com.fintech.disbursement.infrastructure.adapter.in.messaging;

import com.fintech.disbursement.application.RequestDisbursementCommand;
import com.fintech.disbursement.application.port.in.RequestDisbursementUseCase;
import com.fintech.disbursement.domain.DisbursementSource;
import com.fintech.disbursement.domain.Rail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/** ACL — retiro de saldo a favor. Tercer origen, mismo núcleo, cero cambios en el núcleo. */
@Component
public class WalletWithdrawalCompletedListener {

    private static final Logger log = LoggerFactory.getLogger(WalletWithdrawalCompletedListener.class);
    private static final String SOURCE_SYSTEM = "wallet";

    private final RequestDisbursementUseCase requestDisbursement;

    public WalletWithdrawalCompletedListener(RequestDisbursementUseCase requestDisbursement) {
        this.requestDisbursement = requestDisbursement;
    }

    @KafkaListener(
            topics = "${fintech.disbursement.topics.inbound.wallet-withdrawal-completed}",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "walletWithdrawalListenerContainerFactory")
    public void onWithdrawalCompleted(WalletWithdrawalCompletedPayload event) {
        if (event.amount() == null || event.amount().signum() <= 0) {
            log.debug("withdrawal-completed sin monto withdrawalId={}", event.withdrawalId());
            return;
        }

        requestDisbursement.request(new RequestDisbursementCommand(
                SOURCE_SYSTEM,
                DisbursementSource.WITHDRAWAL,
                String.valueOf(event.withdrawalId()),
                String.valueOf(event.withdrawalId()),
                null,
                event.companyId(),
                Map.of("walletId", String.valueOf(event.walletId())),
                event.beneficiaryName(),
                event.beneficiaryAccount(),
                event.beneficiaryAccountType(),
                event.beneficiaryTaxId(),
                event.beneficiaryInstitution(),
                event.amount(),
                event.currency(),
                event.concept(),
                null,
                Rail.SPEI,
                String.valueOf(event.withdrawalId())));
    }
}

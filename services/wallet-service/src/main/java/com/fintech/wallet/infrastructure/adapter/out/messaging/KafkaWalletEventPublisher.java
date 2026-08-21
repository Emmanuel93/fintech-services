package com.fintech.wallet.infrastructure.adapter.out.messaging;

import com.fintech.wallet.application.port.out.WalletEventPublisher;
import com.fintech.wallet.domain.PaymentInstruction;
import com.fintech.wallet.domain.WalletView;
import com.fintech.wallet.domain.WalletWithdrawal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Component
public class KafkaWalletEventPublisher implements WalletEventPublisher {

    static final String TOPIC_PAYMENT_INSTRUCTION_CREATED = "wallet.payment-instruction-created";
    static final String TOPIC_DISPOSITION_REQUESTED       = "wallet.disposition-requested";
    static final String TOPIC_SNAPSHOT_UPDATED            = "wallet.snapshot-updated";
    static final String TOPIC_WITHDRAWAL_COMPLETED        = "wallet.withdrawal-completed";

    private static final Logger log = LoggerFactory.getLogger(KafkaWalletEventPublisher.class);

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public KafkaWalletEventPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public void publishPaymentInstructionCreated(PaymentInstruction instruction) {
        var payload = new PaymentInstructionCreatedPayload(
                instruction.getInstructionId(),
                instruction.getCreditAccountId(),
                instruction.getObligorPartyId(),
                instruction.getPaymentMethod(),
                instruction.getAmount(),
                instruction.getPaymentType(),
                instruction.getExpiresAt(),
                instruction.getPaymentRef(),
                Instant.now());
        kafkaTemplate.send(TOPIC_PAYMENT_INSTRUCTION_CREATED,
                        instruction.getCreditAccountId().toString(), payload)
                .whenComplete((r, ex) -> {
                    if (ex != null) log.error("payment-instruction-created publish failed instructionId={}: {}",
                            instruction.getInstructionId(), ex.getMessage());
                    else log.debug("payment-instruction-created published instructionId={}",
                            instruction.getInstructionId());
                });
    }

    @Override
    public UUID publishDispositionRequested(UUID creditAccountId, UUID obligorPartyId,
                                             BigDecimal amount, String dispositionType,
                                             UUID beneficiaryPartyId, String payeeAccount,
                                             Integer termPeriods) {
        UUID dispositionRequestId = UUID.randomUUID();
        var payload = new DispositionRequestedPayload(
                dispositionRequestId, creditAccountId, obligorPartyId, amount, dispositionType,
                beneficiaryPartyId, payeeAccount, termPeriods, Instant.now());
        kafkaTemplate.send(TOPIC_DISPOSITION_REQUESTED, creditAccountId.toString(), payload)
                .whenComplete((r, ex) -> {
                    if (ex != null) log.error("disposition-requested publish failed creditAccountId={}: {}",
                            creditAccountId, ex.getMessage());
                    else log.debug("disposition-requested published creditAccountId={} dispositionRequestId={}",
                            creditAccountId, dispositionRequestId);
                });
        return dispositionRequestId;
    }

    @Override
    public void publishWalletSnapshotUpdated(WalletView view) {
        var payload = new WalletSnapshotUpdatedPayload(
                view.getWalletId(),
                view.getCreditAccountId(),
                view.getTotalDebt(),
                view.getAvailableCredit(),
                view.getMinimumPayment(),
                view.getPaymentDueDate(),
                view.getLastUpdatedAt());
        kafkaTemplate.send(TOPIC_SNAPSHOT_UPDATED, view.getCreditAccountId().toString(), payload)
                .whenComplete((r, ex) -> {
                    if (ex != null) log.error("wallet-snapshot-updated publish failed walletId={}: {}",
                            view.getWalletId(), ex.getMessage());
                    else log.debug("wallet-snapshot-updated published walletId={}", view.getWalletId());
                });
    }

    @Override
    public void publishWithdrawalCompleted(WalletWithdrawal withdrawal) {
        var payload = new WithdrawalCompletedPayload(
                withdrawal.getWithdrawalId(),
                withdrawal.getCreditAccountId(),
                withdrawal.getObligorPartyId(),
                withdrawal.getMethod(),
                withdrawal.getAmount(),
                withdrawal.getPayeeAccount(),
                withdrawal.getExternalRef(),
                Instant.now());
        kafkaTemplate.send(TOPIC_WITHDRAWAL_COMPLETED, withdrawal.getCreditAccountId().toString(), payload)
                .whenComplete((r, ex) -> {
                    if (ex != null) log.error("withdrawal-completed publish failed withdrawalId={}: {}",
                            withdrawal.getWithdrawalId(), ex.getMessage());
                    else log.debug("withdrawal-completed published withdrawalId={}", withdrawal.getWithdrawalId());
                });
    }
}

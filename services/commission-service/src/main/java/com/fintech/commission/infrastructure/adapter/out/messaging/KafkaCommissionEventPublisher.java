package com.fintech.commission.infrastructure.adapter.out.messaging;

import com.fintech.commission.application.port.out.CommissionEventPublisher;
import com.fintech.commission.domain.CommissionRecord;
import com.fintech.commission.domain.LiquidationBatch;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class KafkaCommissionEventPublisher implements CommissionEventPublisher {

    static final String TOPIC_ACCRUED     = "commission.commission-accrued";
    static final String TOPIC_REVERSED    = "commission.commission-reversed";
    static final String TOPIC_LIQUIDATED  = "commission.commission-liquidated";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public KafkaCommissionEventPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public void publishCommissionAccrued(CommissionRecord r) {
        var payload = new CommissionAccruedPayload(r.getCommissionId(), r.getCommissionType().name(),
                r.getCreditAccountId(), r.getBeneficiaryPartyId(), r.getBasis(), r.getRate(), r.getAmount(),
                r.getPeriod(), r.getAccrualDate());
        kafkaTemplate.send(TOPIC_ACCRUED, r.getCreditAccountId().toString(), payload);
    }

    @Override
    public void publishCommissionReversed(CommissionRecord r) {
        var payload = new CommissionReversedPayload(r.getCommissionId(), r.getCreditAccountId(),
                r.getBeneficiaryPartyId(), r.getAmount());
        kafkaTemplate.send(TOPIC_REVERSED, r.getCreditAccountId().toString(), payload);
    }

    @Override
    public void publishCommissionLiquidated(LiquidationBatch b) {
        var payload = new CommissionLiquidatedPayload(b.getBatchId(), b.getBeneficiaryPartyId(),
                b.getPeriod(), b.getTotalAmount());
        kafkaTemplate.send(TOPIC_LIQUIDATED, b.getBeneficiaryPartyId().toString(), payload);
    }
}

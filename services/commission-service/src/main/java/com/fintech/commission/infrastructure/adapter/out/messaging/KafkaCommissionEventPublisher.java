package com.fintech.commission.infrastructure.adapter.out.messaging;

import com.fintech.commission.application.port.out.CommissionEventPublisher;
import com.fintech.commission.domain.BeneficiaryProfile;
import com.fintech.commission.domain.CommissionRecord;
import com.fintech.commission.domain.LiquidationBatch;
import com.fintech.shared.time.BusinessClock;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;

@Component
public class KafkaCommissionEventPublisher implements CommissionEventPublisher {

    static final String TOPIC_ACCRUED                = "commission.commission-accrued";
    static final String TOPIC_REVERSED               = "commission.commission-reversed";
    static final String TOPIC_LOT_PAYOUT_REQUESTED   = "commission.lot-payout-requested";
    static final String TOPIC_LOT_PAYOUT_FAILED      = "commission.lot-payout-failed";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public KafkaCommissionEventPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public void publishCommissionAccrued(CommissionRecord r) {
        var payload = new CommissionAccruedPayload(r.getCommissionId(), r.getCommissionType().name(),
                r.getCreditAccountId(), r.getBeneficiaryPartyId(), r.getProductType(), r.getPaymentRef(),
                r.getBasis(), r.getRate(), r.getAmount(), r.getIvaAmount(), r.getPeriod(), r.getValueDate(),
                noonOf(r.getValueDate()), r.getAccrualDate(), r.getSettlement());
        kafkaTemplate.send(TOPIC_ACCRUED, r.getCreditAccountId().toString(), payload);
    }

    @Override
    public void publishCommissionReversed(CommissionRecord reversal) {
        var payload = new CommissionReversedPayload(reversal.getCommissionId(), reversal.getReversesCommissionId(),
                reversal.getCreditAccountId(), reversal.getBeneficiaryPartyId(), reversal.getProductType(),
                reversal.getAmount().negate(), reversal.getIvaAmount().negate(), noonOf(reversal.getValueDate()),
                reversal.getSettlement());
        kafkaTemplate.send(TOPIC_REVERSED, reversal.getCreditAccountId().toString(), payload);
    }

    @Override
    public void publishLotPayoutRequested(LiquidationBatch lot, BeneficiaryProfile beneficiary, int attempt) {
        var payload = new LotPayoutRequestedPayload(lot.getBatchId(), lot.itemRef(), attempt,
                lot.getBeneficiaryPartyId(), lot.getProductType(), lot.getPeriod(), lot.getSubtotal(),
                lot.getIvaAmount(), lot.getIsrWithheld(), lot.getIvaWithheld(), lot.getTotalAmount(),
                lot.getNetAmount(), beneficiary.getPayoutHolder(), beneficiary.getPayoutClabe(), beneficiary.getRfc(),
                conceptOf(lot),
                BusinessClock.current().now());
        kafkaTemplate.send(TOPIC_LOT_PAYOUT_REQUESTED, lot.getBatchId().toString(), payload);
    }

    @Override
    public void publishLotPayoutFailed(LiquidationBatch lot, int attempt, String reason) {
        var payload = new LotPayoutFailedPayload(lot.getBatchId(), lot.itemRef(), attempt, lot.getBeneficiaryPartyId(),
                lot.getProductType(), lot.getSubtotal(), lot.getIvaAmount(), lot.getIsrWithheld(), lot.getIvaWithheld(),
                lot.getTotalAmount(), lot.getNetAmount(), reason, BusinessClock.current().now());
        kafkaTemplate.send(TOPIC_LOT_PAYOUT_FAILED, lot.getBatchId().toString(), payload);
    }

    /**
     * The SPEI concept: the period and the lot, within the 40 characters the rail takes (a product name like
     * BUSINESS_REVOLVING_LINE did not fit, and the order would have been refused).
     */
    static String conceptOf(LiquidationBatch lot) {
        return "Comisiones " + lot.getPeriod() + " lote " + lot.getBatchId().toString().substring(0, 8);
    }

    /** Noon of a business day in the business zone: the instant that lands on that day whatever reads it. */
    private static Instant noonOf(LocalDate day) {
        LocalDate d = day != null ? day : BusinessClock.current().today();
        return d.atTime(12, 0).atZone(BusinessClock.current().zone()).toInstant();
    }
}

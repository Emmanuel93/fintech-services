package com.fintech.accounting.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fintech.accounting.application.CommissionMoves.LotPayout;
import com.fintech.accounting.application.service.CommissionPostingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A lot of commissions to pay by SPEI ({@code commission.lot-payout-requested}) and an order of it that failed or came
 * back ({@code commission.lot-payout-failed}). The money leaves Bancos when the bank reports the order, not here.
 */
@Component
public class CommissionLotPayoutListener {

    private static final Logger log = LoggerFactory.getLogger(CommissionLotPayoutListener.class);
    private final CommissionPostingService commissions;

    public CommissionLotPayoutListener(CommissionPostingService commissions) {
        this.commissions = commissions;
    }

    @KafkaListener(topics = "${fintech.accounting.topics.commission-lot-payout-requested:commission.lot-payout-requested}",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "commissionLotPayoutListenerContainerFactory")
    public void onRequested(LotPayoutPayload p) {
        log.debug("lot-payout-requested batchId={} attempt={} gross={} net={}", p.batchId(), p.attempt(),
                p.grossAmount(), p.netAmount());
        commissions.onLotPayoutRequested(p.toLot());
    }

    @KafkaListener(topics = "${fintech.accounting.topics.commission-lot-payout-failed:commission.lot-payout-failed}",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "commissionLotPayoutListenerContainerFactory")
    public void onFailed(LotPayoutPayload p) {
        log.debug("lot-payout-failed batchId={} attempt={}", p.batchId(), p.attempt());
        commissions.onLotPayoutFailed(p.toLot());
    }

    /** What both facts carry of the lot: its taxes, the gross owed and the net the SPEI pays. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LotPayoutPayload(UUID batchId, int attempt, UUID beneficiaryPartyId, String productType,
                                   BigDecimal subtotal, BigDecimal ivaAmount, BigDecimal isrWithheld,
                                   BigDecimal ivaWithheld, BigDecimal grossAmount, BigDecimal netAmount,
                                   Instant occurredOn) {

        LotPayout toLot() {
            return new LotPayout(batchId, attempt, beneficiaryPartyId, productType, subtotal, ivaAmount, isrWithheld,
                    ivaWithheld, grossAmount, netAmount, occurredOn);
        }
    }
}

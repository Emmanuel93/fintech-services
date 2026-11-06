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
 * ACL — a lot of commissions to pay. Fourth origin, same core: the order pays the net to the beneficiary's CLABE.
 *
 * <p>The item ({@code LOTE:<id>}) is the reference every service names the payment by: accounting clears its SPEI in
 * flight (2103) with it. Each attempt is its own order (its key is the lot and the attempt): a lot whose order failed is
 * asked again with the next attempt, and a redelivery of the same attempt is the same order.
 */
@Component
public class CommissionLotPayoutListener {

    private static final Logger log = LoggerFactory.getLogger(CommissionLotPayoutListener.class);
    static final String SOURCE_SYSTEM = "commission";

    private final RequestDisbursementUseCase requestDisbursement;

    public CommissionLotPayoutListener(RequestDisbursementUseCase requestDisbursement) {
        this.requestDisbursement = requestDisbursement;
    }

    @KafkaListener(
            topics = "${fintech.disbursement.topics.inbound.commission-lot-payout-requested:commission.lot-payout-requested}",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "commissionLotPayoutListenerContainerFactory")
    public void onLotPayoutRequested(CommissionLotPayoutPayload event) {
        if (event.batchId() == null || event.netAmount() == null || event.netAmount().signum() <= 0) {
            log.warn("lot-payout-requested sin lote o sin neto batchId={} neto={}", event.batchId(), event.netAmount());
            return;
        }
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("batchId", event.batchId().toString());
        metadata.put("attempt", String.valueOf(event.attempt()));
        if (event.beneficiaryPartyId() != null) metadata.put("beneficiaryPartyId", event.beneficiaryPartyId().toString());
        if (event.productType() != null) metadata.put("productType", event.productType());
        if (event.period() != null) metadata.put("period", event.period());

        requestDisbursement.requestOrReject(new RequestDisbursementCommand(
                SOURCE_SYSTEM,
                DisbursementSource.COMMISSION,
                event.itemRef() != null ? event.itemRef() : "LOTE:" + event.batchId(),
                event.batchId() + ":" + event.attempt(),
                null,
                null,
                metadata,
                event.beneficiaryName(),
                event.beneficiaryAccount(),
                null,
                event.beneficiaryTaxId(),
                null,
                event.netAmount(),
                "MXN",
                event.concept(),
                null,
                Rail.SPEI,
                event.batchId().toString()));
    }
}

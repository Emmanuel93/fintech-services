package com.fintech.accounting.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fintech.accounting.application.service.PayoutLinkProjection;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * The connector took an outgoing order ({@code disbursement.accepted}). Accounting keeps only what it pays out: the
 * loan, from the metadata the portfolio sent and disbursement echoes, or the lot of commissions, from the reference
 * commission sent. The disbursement id is the connector's payment request, the key the bank's report of the order
 * comes with.
 */
@Component
public class DisbursementAcceptedListener {

    /** What is read of the acceptance; the rest (provider, references, correlation) is ignored. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DisbursementAcceptedPayload(UUID disbursementId, String sourceSystem, String sourceReference,
                                              Map<String, String> sourceMetadata, BigDecimal amount) {

        /** A lot of commissions names itself by its item ({@code LOTE:<id>}), the reference commission sent. */
        boolean paysALot() {
            return COMMISSION.equals(sourceSystem) && sourceReference != null && sourceReference.startsWith("LOTE:");
        }

        /** The loan, as the portfolio wrote it in the metadata; none for an order that does not pay out a loan. */
        UUID creditAccountId() {
            String raw = sourceMetadata == null ? null : sourceMetadata.get("creditAccountId");
            if (raw == null || raw.isBlank() || "null".equals(raw)) return null;
            try {
                return UUID.fromString(raw.trim());
            } catch (IllegalArgumentException notAnId) {
                return null;
            }
        }
    }

    static final String COMMISSION = "commission";

    private final PayoutLinkProjection links;

    public DisbursementAcceptedListener(PayoutLinkProjection links) {
        this.links = links;
    }

    @KafkaListener(topics = "${fintech.accounting.topics.disbursement-accepted:disbursement.accepted}",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "disbursementAcceptedListenerContainerFactory")
    public void onMessage(DisbursementAcceptedPayload p) {
        if (p.paysALot()) {
            links.onItemOrderAccepted(p.disbursementId(), p.sourceReference(), p.sourceReference(), p.amount());
            return;
        }
        links.onOrderAccepted(p.disbursementId(), p.creditAccountId(), p.sourceReference(), p.amount());
    }
}

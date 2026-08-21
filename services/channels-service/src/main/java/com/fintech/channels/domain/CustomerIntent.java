package com.fintech.channels.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "channels", name = "customer_intents")
public class CustomerIntent {

    @Id
    @Column(name = "intent_id")
    private UUID intentId;

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Column(name = "intent_type", nullable = false)
    private String intentType;

    @Column(nullable = false)
    private String status;

    @Column(name = "routed_to")
    private String routedTo;

    @Column(name = "handoff_event_id")
    private UUID handoffEventId;

    @Column(name = "product_type_hint")
    private String productTypeHint;

    @Column(name = "requested_amount", precision = 15, scale = 2)
    private BigDecimal requestedAmount;

    @Column(name = "promoter_code")
    private String promoterCode;

    @Column(name = "abandon_reason")
    private String abandonReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CustomerIntent() {}

    public static CustomerIntent capture(UUID sessionId, IntentType intentType,
                                         String productTypeHint, BigDecimal requestedAmount,
                                         String promoterCode) {
        var ci = new CustomerIntent();
        ci.intentId = UUID.randomUUID();
        ci.sessionId = sessionId;
        ci.intentType = intentType.name();
        ci.status = IntentStatus.CAPTURED.name();
        ci.productTypeHint = productTypeHint;
        ci.requestedAmount = requestedAmount;
        ci.promoterCode = promoterCode;
        ci.createdAt = Instant.now();
        ci.updatedAt = ci.createdAt;
        return ci;
    }

    public void route(DomainTarget target) {
        this.routedTo = target.name();
        this.status = IntentStatus.ROUTED.name();
        this.updatedAt = Instant.now();
    }

    public void abandon(String reason) {
        this.abandonReason = reason;
        this.status = IntentStatus.ABANDONED.name();
        this.updatedAt = Instant.now();
    }

    public boolean isCaptured() { return IntentStatus.CAPTURED.name().equals(status); }

    public UUID getIntentId() { return intentId; }
    public UUID getSessionId() { return sessionId; }
    public String getIntentType() { return intentType; }
    public String getStatus() { return status; }
    public String getRoutedTo() { return routedTo; }
    public UUID getHandoffEventId() { return handoffEventId; }
    public String getProductTypeHint() { return productTypeHint; }
    public BigDecimal getRequestedAmount() { return requestedAmount; }
    public String getPromoterCode() { return promoterCode; }
    public String getAbandonReason() { return abandonReason; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}

package com.fintech.audit.infrastructure.adapter.in.messaging;

import com.fintech.audit.application.service.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class CreditPortfolioEventListener {

    private static final Logger log = LoggerFactory.getLogger(CreditPortfolioEventListener.class);
    private static final String SOURCE = "credit-portfolio-service";

    private final AuditService auditService;

    public CreditPortfolioEventListener(AuditService auditService) {
        this.auditService = auditService;
    }

    @KafkaListener(topics = "credit-portfolio.credit-account-activated",
            groupId = "audit-service",
            containerFactory = "auditListenerContainerFactory")
    public void onAccountActivated(String payload) {
        Map<String, Object> p = EventPayloadParser.parse(payload);
        String aggregateId = EventPayloadParser.field(p, "creditAccountId", "id");
        String partyId     = EventPayloadParser.field(p, "obligorPartyId", "partyId");
        auditService.record("CREDIT_PORTFOLIO_ACCOUNT_ACTIVATED", SOURCE, aggregateId, partyId, null, payload);
        log.info("audited CREDIT_PORTFOLIO_ACCOUNT_ACTIVATED aggregateId={}", aggregateId);
    }

    @KafkaListener(topics = "credit-portfolio.balance-updated",
            groupId = "audit-service",
            containerFactory = "auditListenerContainerFactory")
    public void onBalanceUpdated(String payload) {
        Map<String, Object> p = EventPayloadParser.parse(payload);
        String aggregateId = EventPayloadParser.field(p, "creditAccountId", "id");
        String partyId     = EventPayloadParser.field(p, "obligorPartyId", "partyId");
        auditService.record("CREDIT_PORTFOLIO_BALANCE_UPDATED", SOURCE, aggregateId, partyId, null, payload);
        log.debug("audited CREDIT_PORTFOLIO_BALANCE_UPDATED aggregateId={}", aggregateId);
    }

    @KafkaListener(topics = "credit-portfolio.payment-rejected",
            groupId = "audit-service",
            containerFactory = "auditListenerContainerFactory")
    public void onPaymentRejected(String payload) {
        Map<String, Object> p = EventPayloadParser.parse(payload);
        String aggregateId = EventPayloadParser.field(p, "creditAccountId", "id");
        auditService.record("CREDIT_PORTFOLIO_PAYMENT_REJECTED", SOURCE, aggregateId, null, null, payload);
        log.info("audited CREDIT_PORTFOLIO_PAYMENT_REJECTED aggregateId={}", aggregateId);
    }

    @KafkaListener(topics = "credit-portfolio.charge-rejected",
            groupId = "audit-service",
            containerFactory = "auditListenerContainerFactory")
    public void onChargeRejected(String payload) {
        Map<String, Object> p = EventPayloadParser.parse(payload);
        String aggregateId = EventPayloadParser.field(p, "creditAccountId", "id");
        auditService.record("CREDIT_PORTFOLIO_CHARGE_REJECTED", SOURCE, aggregateId, null, null, payload);
        log.info("audited CREDIT_PORTFOLIO_CHARGE_REJECTED aggregateId={}", aggregateId);
    }
}

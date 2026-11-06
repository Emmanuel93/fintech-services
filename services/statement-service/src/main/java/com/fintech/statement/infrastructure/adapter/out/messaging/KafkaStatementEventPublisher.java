package com.fintech.statement.infrastructure.adapter.out.messaging;

import com.fintech.statement.application.port.out.StatementEventPublisher;
import com.fintech.statement.domain.Statement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;
import com.fintech.shared.time.BusinessClock;

@Component
class KafkaStatementEventPublisher implements StatementEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaStatementEventPublisher.class);

    static final String TOPIC_GENERATED = "statement.statement-generated";

    private final KafkaTemplate<String, Object> kafka;

    KafkaStatementEventPublisher(KafkaTemplate<String, Object> kafka) {
        this.kafka = kafka;
    }

    @Override
    public void publishGenerated(Statement s) {
        var payload = new StatementGeneratedPayload(UUID.randomUUID().toString(), s.statementId(), s.runId(),
                s.creditAccountId(), s.obligorPartyId(), s.holderKind(), s.productType(), s.layout().name(),
                s.cycleNumber(), s.periodStart(), s.cutoffDate(), s.paymentDueDate(), s.balanceAtCutoff(),
                s.amountDue(), s.minimumPayment(), "/api/v1/statements/" + s.statementId() + "/pdf", BusinessClock.current().now());
        kafka.send(TOPIC_GENERATED, s.creditAccountId().toString(), payload).whenComplete((r, ex) -> {
            if (ex != null) log.error("statement-generated no salió statementId={}: {}", s.statementId(), ex.getMessage());
        });
    }
}

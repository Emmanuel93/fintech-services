package com.fintech.origination.infrastructure.adapter.out.messaging;

import com.fintech.origination.application.port.out.ApplicationEventPublisher;
import com.fintech.origination.domain.event.ApplicationApprovedEvent;
import com.fintech.origination.domain.event.ApplicationRejectedEvent;
import com.fintech.origination.domain.event.DocumentsRequestedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class KafkaApplicationEventPublisher implements ApplicationEventPublisher {

    static final String TOPIC_APPLICATION_APPROVED = "origination.application-approved";
    static final String TOPIC_APPLICATION_REJECTED  = "origination.application-rejected";
    static final String TOPIC_DOCUMENTS_REQUESTED   = "origination.documents-requested";

    private static final Logger log = LoggerFactory.getLogger(KafkaApplicationEventPublisher.class);

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public KafkaApplicationEventPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public void publishApplicationApproved(ApplicationApprovedEvent event) {
        kafkaTemplate.send(TOPIC_APPLICATION_APPROVED, event.getApplicationId().toString(), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("ApplicationApproved publish failed applicationId={}: {}",
                                event.getApplicationId(), ex.getMessage());
                    } else {
                        log.info("ApplicationApproved published applicationId={} flow={} decidedBy={}",
                                event.getApplicationId(), event.getApprovalFlow(), event.getDecidedBy());
                    }
                });
    }

    @Override
    public void publishApplicationRejected(ApplicationRejectedEvent event) {
        kafkaTemplate.send(TOPIC_APPLICATION_REJECTED, event.getApplicationId().toString(), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("ApplicationRejected publish failed applicationId={}: {}",
                                event.getApplicationId(), ex.getMessage());
                    } else {
                        log.warn("ApplicationRejected published applicationId={} flow={} decidedBy={}",
                                event.getApplicationId(), event.getApprovalFlow(), event.getDecidedBy());
                    }
                });
    }

    @Override
    public void publishDocumentsRequested(DocumentsRequestedEvent event) {
        kafkaTemplate.send(TOPIC_DOCUMENTS_REQUESTED, event.getApplicationId().toString(), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("DocumentsRequested publish failed applicationId={}: {}",
                                event.getApplicationId(), ex.getMessage());
                    } else {
                        log.info("DocumentsRequested published applicationId={} deadline={}",
                                event.getApplicationId(), event.getDeadline());
                    }
                });
    }
}

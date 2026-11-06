package com.fintech.payments.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fintech.payments.application.port.out.ClientReferenceDirectory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Keeps the directory of the distributors' clients' references ({@code credit-portfolio.client-reference-assigned}). */
@Component
public class ClientReferenceAssignedListener {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ClientReferenceAssignedPayload(UUID creditAccountId, UUID obligorPartyId, UUID beneficiaryPartyId,
                                                 Long paymentReference) {}

    private final ClientReferenceDirectory directory;

    public ClientReferenceAssignedListener(ClientReferenceDirectory directory) {
        this.directory = directory;
    }

    @KafkaListener(topics = "credit-portfolio.client-reference-assigned", groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "clientReferenceAssignedListenerContainerFactory")
    public void onAssigned(ClientReferenceAssignedPayload event) {
        if (event.paymentReference() == null || event.creditAccountId() == null || event.beneficiaryPartyId() == null) {
            return;
        }
        directory.record(event.paymentReference(), event.creditAccountId(), event.obligorPartyId(), event.beneficiaryPartyId());
    }
}

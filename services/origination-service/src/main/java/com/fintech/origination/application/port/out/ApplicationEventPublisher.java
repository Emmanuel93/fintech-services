package com.fintech.origination.application.port.out;

import com.fintech.origination.domain.event.ApplicationApprovedEvent;
import com.fintech.origination.domain.event.ApplicationRejectedEvent;
import com.fintech.origination.domain.event.DocumentsRequestedEvent;

public interface ApplicationEventPublisher {
    void publishApplicationApproved(ApplicationApprovedEvent event);
    void publishApplicationRejected(ApplicationRejectedEvent event);
    void publishDocumentsRequested(DocumentsRequestedEvent event);
}

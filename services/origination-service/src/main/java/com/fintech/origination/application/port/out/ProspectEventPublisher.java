package com.fintech.origination.application.port.out;

import com.fintech.origination.domain.event.ProspectCreatedEvent;

public interface ProspectEventPublisher {

    void publish(ProspectCreatedEvent event);
}

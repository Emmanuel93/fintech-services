package com.fintech.origination.application.port.out;

import com.fintech.origination.domain.event.OfferPresentedEvent;

public interface OfferEventPublisher {
    void publish(OfferPresentedEvent event);
}

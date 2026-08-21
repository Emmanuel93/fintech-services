package com.fintech.creditproduct.application.port.out;

import com.fintech.creditproduct.domain.event.ProductActivatedEvent;
import com.fintech.creditproduct.domain.event.ProductRetiredEvent;

public interface ProductEventPublisher {
    void publishProductActivated(ProductActivatedEvent event);
    void publishProductRetired(ProductRetiredEvent event);
}

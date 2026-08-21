package com.fintech.channels.application.port.out;

import com.fintech.channels.domain.CustomerIntent;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CustomerIntentRepository {
    Optional<CustomerIntent> findById(UUID intentId);
    Optional<CustomerIntent> findByIdAndSessionId(UUID intentId, UUID sessionId);
    List<CustomerIntent> findBySessionId(UUID sessionId);
    List<CustomerIntent> findCapturedBySessionId(UUID sessionId);
    CustomerIntent save(CustomerIntent intent);
}

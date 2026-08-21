package com.fintech.channels.infrastructure.adapter.out.persistence;

import com.fintech.channels.application.port.out.CustomerIntentRepository;
import com.fintech.channels.domain.CustomerIntent;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class JpaCustomerIntentAdapter implements CustomerIntentRepository {

    private final SpringDataCustomerIntentRepository jpa;

    JpaCustomerIntentAdapter(SpringDataCustomerIntentRepository jpa) {
        this.jpa = jpa;
    }

    @Override public Optional<CustomerIntent> findById(UUID id) { return jpa.findById(id); }
    @Override public CustomerIntent save(CustomerIntent intent) { return jpa.save(intent); }
    @Override public List<CustomerIntent> findBySessionId(UUID sessionId) { return jpa.findBySessionId(sessionId); }
    @Override public List<CustomerIntent> findCapturedBySessionId(UUID sessionId) { return jpa.findCapturedBySessionId(sessionId); }

    @Override
    public Optional<CustomerIntent> findByIdAndSessionId(UUID intentId, UUID sessionId) {
        return jpa.findByIntentIdAndSessionId(intentId, sessionId);
    }
}

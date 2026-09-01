package com.fintech.banking.infrastructure.adapter.out.persistence;

import com.fintech.banking.application.port.out.PayoutRouteRepository;
import com.fintech.banking.domain.PayoutRoute;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class JpaPayoutRouteAdapter implements PayoutRouteRepository {

    private final SpringDataPayoutRouteRepository jpa;

    JpaPayoutRouteAdapter(SpringDataPayoutRouteRepository jpa) { this.jpa = jpa; }

    @Override public PayoutRoute save(PayoutRoute ruta)      { return jpa.save(ruta); }
    @Override public Optional<PayoutRoute> findById(UUID id) { return jpa.findById(id); }
    @Override public List<PayoutRoute> findAllEnabled()      { return jpa.findByEnabledTrue(); }
    @Override public List<PayoutRoute> findAll()             { return jpa.findAll(); }
}

package com.fintech.creditportfolio.infrastructure.adapter.out.persistence;

import com.fintech.creditportfolio.application.port.out.BalanceEventRepository;
import com.fintech.creditportfolio.domain.BalanceEvent;
import org.springframework.stereotype.Repository;

@Repository
public class JpaBalanceEventAdapter implements BalanceEventRepository {

    private final SpringDataBalanceEventRepository repository;

    public JpaBalanceEventAdapter(SpringDataBalanceEventRepository repository) {
        this.repository = repository;
    }

    @Override
    public BalanceEvent save(BalanceEvent event) {
        return repository.save(event);
    }

    @Override
    public boolean existsBySourceEventId(String sourceEventId) {
        return repository.existsBySourceEventId(sourceEventId);
    }

    @Override
    public long countByAccountId(java.util.UUID accountId) {
        return repository.countByCreditAccountId(accountId);
    }
}

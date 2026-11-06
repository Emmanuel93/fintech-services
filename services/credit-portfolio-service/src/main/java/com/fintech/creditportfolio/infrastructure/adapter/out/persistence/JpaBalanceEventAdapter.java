package com.fintech.creditportfolio.infrastructure.adapter.out.persistence;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import java.util.List;
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
    public Optional<BalanceEvent> findBySourceEventId(String sourceEventId) {
        return repository.findBySourceEventId(sourceEventId);
    }

    @Override
    public Optional<BalanceEvent> findPrevious(BalanceEvent event) {
        return repository.findFirstByCreditAccountIdAndAppliedAtLessThanOrderByAppliedAtDesc(
                event.getCreditAccountId(), event.getAppliedAt());
    }

    @Override
    public long countByAccountId(UUID accountId) {
        return repository.countByCreditAccountId(accountId);
    }

    @Override
    public List<BalanceEvent> findBetween(UUID accountId, LocalDate from,
                                                   LocalDate to) {
        return repository.findByCreditAccountIdAndValueDateBetweenOrderByValueDateAscAppliedAtAsc(accountId, from, to);
    }

    @Override
    public Optional<BalanceEvent> findLastBefore(UUID accountId, LocalDate before) {
        return repository.findFirstByCreditAccountIdAndValueDateLessThanOrderByValueDateDescAppliedAtDesc(accountId, before);
    }

    @Override
    public BigDecimal chargesBetween(UUID accountId, LocalDate from, LocalDate to) {
        return repository.chargesBetween(accountId, from, to);
    }

    @Override
    public List<BalanceEvent> paymentsOf(UUID accountId) {
        return repository.paymentsOf(accountId);
    }

    @Override
    public BigDecimal netPaymentsUpTo(UUID accountId, LocalDate to) {
        return repository.netPaymentsUpTo(accountId, to);
    }
}

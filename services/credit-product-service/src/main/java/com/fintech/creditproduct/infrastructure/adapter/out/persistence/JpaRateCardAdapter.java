package com.fintech.creditproduct.infrastructure.adapter.out.persistence;

import com.fintech.creditproduct.application.port.out.RateCardRepository;
import com.fintech.creditproduct.domain.RateCard;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public class JpaRateCardAdapter implements RateCardRepository {

    private final SpringDataRateCardRepository repository;

    public JpaRateCardAdapter(SpringDataRateCardRepository repository) {
        this.repository = repository;
    }

    @Override
    public RateCard save(RateCard rateCard) {
        return repository.save(rateCard);
    }

    @Override
    public List<RateCard> saveAll(Iterable<RateCard> rateCards) {
        return repository.saveAll(rateCards);
    }

    @Override
    public List<RateCard> findByProductDefinitionId(UUID productDefinitionId) {
        return repository.findByProductDefinitionId(productDefinitionId);
    }

    @Override
    public void deleteByProductDefinitionId(UUID productDefinitionId) {
        repository.deleteByProductDefinitionId(productDefinitionId);
    }
}

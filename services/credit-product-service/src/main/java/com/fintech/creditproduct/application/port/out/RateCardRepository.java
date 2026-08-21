package com.fintech.creditproduct.application.port.out;

import com.fintech.creditproduct.domain.RateCard;

import java.util.List;
import java.util.UUID;

public interface RateCardRepository {
    RateCard save(RateCard rateCard);
    List<RateCard> saveAll(Iterable<RateCard> rateCards);
    List<RateCard> findByProductDefinitionId(UUID productDefinitionId);
    void deleteByProductDefinitionId(UUID productDefinitionId);
}

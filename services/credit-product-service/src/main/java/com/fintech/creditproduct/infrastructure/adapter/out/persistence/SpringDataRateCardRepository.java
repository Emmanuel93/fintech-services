package com.fintech.creditproduct.infrastructure.adapter.out.persistence;

import com.fintech.creditproduct.domain.RateCard;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

interface SpringDataRateCardRepository extends JpaRepository<RateCard, UUID> {

    List<RateCard> findByProductDefinitionId(UUID productDefinitionId);

    @Modifying
    @Query("DELETE FROM RateCard r WHERE r.productDefinitionId = :productDefinitionId")
    void deleteByProductDefinitionId(UUID productDefinitionId);
}

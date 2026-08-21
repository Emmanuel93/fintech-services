package com.fintech.risk.infrastructure.adapter.out.persistence;

import com.fintech.risk.application.port.out.RiskProfileRepository;
import com.fintech.risk.domain.Ifrs9Stage;
import com.fintech.risk.domain.RiskProfile;
import com.fintech.risk.domain.RiskProfileStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JpaRiskProfileRepository
        extends JpaRepository<RiskProfile, UUID>, RiskProfileRepository {

    @Override
    Optional<RiskProfile> findByCreditAccountId(UUID creditAccountId);

    @Override
    List<RiskProfile> findByObligorPartyId(UUID obligorPartyId);

    @Override
    List<RiskProfile> findByStatus(RiskProfileStatus status);

    @Override
    boolean existsByCreditAccountId(UUID creditAccountId);

    @Override
    List<RiskProfile> findByCreditAccountIdIn(Collection<UUID> creditAccountIds);

    @Override
    @Query("SELECT p FROM RiskProfile p "
         + "WHERE (:partyId IS NULL OR p.obligorPartyId = :partyId) "
         + "  AND (:productType IS NULL OR p.productType = :productType) "
         + "  AND (:stage IS NULL OR p.ifrs9Stage = :stage) "
         + "  AND (:status IS NULL OR p.status = :status)")
    Page<RiskProfile> search(@Param("partyId") UUID obligorPartyId,
                             @Param("productType") String productType,
                             @Param("stage") Ifrs9Stage stage,
                             @Param("status") RiskProfileStatus status,
                             Pageable pageable);
}

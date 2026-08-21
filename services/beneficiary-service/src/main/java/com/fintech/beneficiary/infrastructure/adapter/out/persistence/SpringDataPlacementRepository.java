package com.fintech.beneficiary.infrastructure.adapter.out.persistence;

import com.fintech.beneficiary.application.port.out.PlacementRepository;
import com.fintech.beneficiary.domain.Placement;
import com.fintech.beneficiary.domain.PlacementStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SpringDataPlacementRepository
        extends JpaRepository<Placement, UUID>, PlacementRepository {

    /**
     * Los estados en los que una segunda liga al mismo número estorbaría: desde que se envía hasta
     * que el distribuidor decide. Después ya no —«enviarle otro préstamo» es una función del
     * diseño, y una persona puede tener varias colocaciones a lo largo del tiempo.
     */
    List<PlacementStatus> PRE_DECISION = List.of(
            PlacementStatus.INVITED, PlacementStatus.KYC_IN_PROGRESS,
            PlacementStatus.KYC_COMPLETED, PlacementStatus.BUREAU_READY);

    @Override
    Optional<Placement> findById(UUID placementId);

    @Override
    List<Placement> findByDistributorPartyIdOrderByCreatedAtDesc(UUID distributorPartyId);

    @Override
    List<Placement> findByDistributorPartyIdAndStatusOrderByCreatedAtDesc(UUID distributorPartyId,
                                                                         PlacementStatus status);

    @Override
    default boolean existsLivePlacementFor(UUID distributorPartyId, String beneficiaryPhone) {
        return countPreDecisionFor(distributorPartyId, beneficiaryPhone, PRE_DECISION) > 0;
    }

    @Query("""
            SELECT COUNT(p) FROM Placement p
             WHERE p.distributorPartyId = :distributorPartyId
               AND p.beneficiaryPhone   = :phone
               AND p.status IN :statuses
            """)
    long countPreDecisionFor(@Param("distributorPartyId") UUID distributorPartyId,
                             @Param("phone") String phone,
                             @Param("statuses") List<PlacementStatus> statuses);
}

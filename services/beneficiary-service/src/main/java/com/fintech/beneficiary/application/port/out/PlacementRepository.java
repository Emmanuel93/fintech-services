package com.fintech.beneficiary.application.port.out;

import com.fintech.beneficiary.domain.Placement;
import com.fintech.beneficiary.domain.PlacementStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PlacementRepository {

    Optional<Placement> findById(UUID placementId);

    Placement save(Placement placement);

    List<Placement> findByDistributorPartyIdOrderByCreatedAtDesc(UUID distributorPartyId);

    List<Placement> findByDistributorPartyIdAndStatusOrderByCreatedAtDesc(UUID distributorPartyId,
                                                                         PlacementStatus status);

    /**
     * Si ese distribuidor ya tiene una colocación viva con ese celular.
     *
     * <p>«Viva» son los estados en los que otra liga estorbaría: desde la invitación hasta que el
     * distribuidor decide. Una ya desembolsada no estorba —«enviarle otro préstamo» es una función
     * del diseño— y una terminada tampoco.
     */
    boolean existsLivePlacementFor(UUID distributorPartyId, String beneficiaryPhone);
}

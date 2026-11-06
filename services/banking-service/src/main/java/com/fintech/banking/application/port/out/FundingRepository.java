package com.fintech.banking.application.port.out;

import com.fintech.banking.domain.FundingMovement;
import com.fintech.banking.domain.FundingSource;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The funding sources and their movements. */
public interface FundingRepository {

    FundingSource save(FundingSource source);

    Optional<FundingSource> find(UUID id);

    List<FundingSource> findAll();

    FundingMovement save(FundingMovement movement);

    List<FundingMovement> movementsOf(UUID sourceId);

    /** The movement an instruction already recorded (same source, type and reference), if it was. */
    Optional<FundingMovement> findMovement(UUID sourceId, FundingMovement.Type type, String reference);
}

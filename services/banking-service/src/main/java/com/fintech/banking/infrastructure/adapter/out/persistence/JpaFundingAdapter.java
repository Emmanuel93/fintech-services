package com.fintech.banking.infrastructure.adapter.out.persistence;

import com.fintech.banking.application.port.out.FundingRepository;
import com.fintech.banking.domain.FundingMovement;
import com.fintech.banking.domain.FundingSource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface SpringDataFundingSourceRepository extends JpaRepository<FundingSource, UUID> {
    List<FundingSource> findAllByOrderByCreatedAtAsc();
}

interface SpringDataFundingMovementRepository extends JpaRepository<FundingMovement, UUID> {
    List<FundingMovement> findByFundingSourceIdOrderByValueDateAscRecordedAtAsc(UUID sourceId);

    Optional<FundingMovement> findByFundingSourceIdAndMovementTypeAndReference(UUID sourceId, String type, String reference);
}

@Repository
class JpaFundingAdapter implements FundingRepository {

    private final SpringDataFundingSourceRepository sources;
    private final SpringDataFundingMovementRepository movements;

    JpaFundingAdapter(SpringDataFundingSourceRepository sources, SpringDataFundingMovementRepository movements) {
        this.sources = sources;
        this.movements = movements;
    }

    @Override public FundingSource save(FundingSource source) { return sources.save(source); }
    @Override public Optional<FundingSource> find(UUID id) { return sources.findById(id); }
    @Override public List<FundingSource> findAll() { return sources.findAllByOrderByCreatedAtAsc(); }
    @Override public FundingMovement save(FundingMovement movement) { return movements.save(movement); }

    @Override
    public List<FundingMovement> movementsOf(UUID sourceId) {
        return movements.findByFundingSourceIdOrderByValueDateAscRecordedAtAsc(sourceId);
    }

    @Override
    public Optional<FundingMovement> findMovement(UUID sourceId, FundingMovement.Type type, String reference) {
        return movements.findByFundingSourceIdAndMovementTypeAndReference(sourceId, type.name(), reference);
    }
}

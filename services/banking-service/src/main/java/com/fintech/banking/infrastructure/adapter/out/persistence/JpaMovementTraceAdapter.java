package com.fintech.banking.infrastructure.adapter.out.persistence;

import com.fintech.banking.application.port.out.MovementTraceRepository;
import com.fintech.banking.domain.MovementTrace;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface SpringDataMovementTraceRepository extends JpaRepository<MovementTrace, UUID> {
    Optional<MovementTrace> findByPayoutId(UUID payoutId);
    Optional<MovementTrace> findByTrackingKey(String trackingKey);
    Optional<MovementTrace> findByLineId(UUID lineId);
    List<MovementTrace> findByCreditAccountId(UUID creditAccountId);
    List<MovementTrace> findByVoucherRef(String voucherRef);

    /** Cualquier eslabón vacío la deja incompleta; se ordenan por antigüedad, que es la urgencia. */
    @Query("""
            SELECT t FROM MovementTrace t
             WHERE t.bankAccountId IS NULL OR t.trackingKey IS NULL
                OR t.lineId IS NULL OR t.voucherRef IS NULL
             ORDER BY t.createdAt ASC
            """)
    List<MovementTrace> findIncompletas(Limit limite);
}

@Repository
class JpaMovementTraceAdapter implements MovementTraceRepository {

    private final SpringDataMovementTraceRepository jpa;

    JpaMovementTraceAdapter(SpringDataMovementTraceRepository jpa) { this.jpa = jpa; }

    @Override public MovementTrace save(MovementTrace t)              { return jpa.save(t); }
    @Override public Optional<MovementTrace> findByPayoutId(UUID id)  { return jpa.findByPayoutId(id); }
    @Override public Optional<MovementTrace> findByTrackingKey(String k) { return jpa.findByTrackingKey(k); }
    @Override public Optional<MovementTrace> findByLineId(UUID id)    { return jpa.findByLineId(id); }
    @Override public List<MovementTrace> findByCreditAccountId(UUID id) { return jpa.findByCreditAccountId(id); }
    @Override public List<MovementTrace> findByVoucherRef(String ref) { return jpa.findByVoucherRef(ref); }
    @Override public List<MovementTrace> findIncompletas(int limite)  { return jpa.findIncompletas(Limit.of(limite)); }
}

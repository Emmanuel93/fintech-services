package com.fintech.banking.infrastructure.adapter.out.persistence;

import com.fintech.banking.application.port.out.InternalMovementLookupPort;
import com.fintech.banking.application.port.out.InternalMovementStore;
import com.fintech.banking.domain.InternalMovement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface SpringDataInternalMovementRepository extends JpaRepository<InternalMovement, UUID> {
    Optional<InternalMovement> findByTrackingKey(String trackingKey);
    List<InternalMovement> findByBusinessDateAndAmount(LocalDate businessDate, BigDecimal amount);
    Optional<InternalMovement> findFirstBySourceEventId(String sourceEventId);
}

@Repository
class JpaInternalMovementAdapter implements InternalMovementLookupPort, InternalMovementStore {

    private final SpringDataInternalMovementRepository jpa;

    JpaInternalMovementAdapter(SpringDataInternalMovementRepository jpa) { this.jpa = jpa; }

    @Override
    public Optional<MovimientoInterno> byTrackingKey(String trackingKey) {
        return jpa.findByTrackingKey(trackingKey).map(JpaInternalMovementAdapter::translate);
    }

    @Override
    public List<MovimientoInterno> byAmountAndDate(BigDecimal amount, LocalDate businessDate) {
        return jpa.findByBusinessDateAndAmount(businessDate, amount).stream()
                .map(JpaInternalMovementAdapter::translate)
                .toList();
    }

    /** Proyecta un hecho interno. Idempotente por el id del evento que lo trajo. */
    @Override
    public InternalMovement save(InternalMovement m) {
        // Un reintento de Kafka volvería ambiguo un cruce que era determinista: el hecho ya visto devuelve el suyo.
        return jpa.findFirstBySourceEventId(m.getSourceEventId()).orElseGet(() -> jpa.save(m));
    }

    @Override
    public boolean contains(String sourceEventId) {
        return jpa.findFirstBySourceEventId(sourceEventId).isPresent();
    }

    private static MovimientoInterno translate(InternalMovement m) {
        return new MovimientoInterno(m.getMovementType(), m.getReference(), m.getAmount(),
                m.getBusinessDate(), m.getTrackingKey(), m.getDirection());
    }
}

package com.fintech.banking.infrastructure.adapter.out.persistence;

import com.fintech.banking.application.port.out.InternalMovementLookupPort;
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
    boolean existsBySourceEventId(String sourceEventId);
}

@Repository
class JpaInternalMovementAdapter implements InternalMovementLookupPort,
        com.fintech.banking.application.service.InternalMovementProjector {

    private final SpringDataInternalMovementRepository jpa;

    JpaInternalMovementAdapter(SpringDataInternalMovementRepository jpa) { this.jpa = jpa; }

    @Override
    public Optional<MovimientoInterno> porClaveDeRastreo(String trackingKey) {
        return jpa.findByTrackingKey(trackingKey).map(JpaInternalMovementAdapter::traducir);
    }

    @Override
    public List<MovimientoInterno> porImporteYFecha(BigDecimal amount, LocalDate businessDate) {
        return jpa.findByBusinessDateAndAmount(businessDate, amount).stream()
                .map(JpaInternalMovementAdapter::traducir)
                .toList();
    }

    /** Proyecta un hecho interno. Idempotente por el id del evento que lo trajo. */
    @Override
    public void proyectar(InternalMovement m) {
        if (jpa.existsBySourceEventId(m.getSourceEventId())) {
            // Un reintento de Kafka volvería ambiguo un cruce que era determinista.
            return;
        }
        jpa.save(m);
    }

    private static MovimientoInterno traducir(InternalMovement m) {
        return new MovimientoInterno(m.getMovementType(), m.getReference(), m.getAmount(),
                m.getBusinessDate(), m.getTrackingKey());
    }
}

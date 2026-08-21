package com.fintech.disbursement.infrastructure.adapter.out.persistence;

import com.fintech.disbursement.application.port.out.DisbursementOrderRepository;
import com.fintech.disbursement.domain.DisbursementOrder;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Clase y no interfaz: el {@code SKIP LOCKED} necesita paginación y reloj, y eso no cabe en una
 * interfaz derivada de Spring Data sin filtrar {@code Pageable} hacia el puerto.
 */
@Component
public class JpaDisbursementOrderAdapter implements DisbursementOrderRepository {

    private final SpringDataDisbursementOrderRepository repository;
    private final Clock clock;

    JpaDisbursementOrderAdapter(SpringDataDisbursementOrderRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    public DisbursementOrder save(DisbursementOrder order) {
        return repository.save(order);
    }

    @Override
    public Optional<DisbursementOrder> findById(UUID disbursementId) {
        return repository.findById(disbursementId);
    }

    @Override
    public Optional<DisbursementOrder> findBySourceKey(String sourceSystem, String sourceType,
                                                       String sourceEventId) {
        return repository.findBySourceSystemAndSourceTypeAndSourceEventId(
                sourceSystem, sourceType, sourceEventId);
    }

    @Override
    public List<DisbursementOrder> lockDueForDispatch(int batchSize) {
        return repository.lockDueForDispatch(clock.instant(), PageRequest.of(0, batchSize));
    }

    @Override
    public List<DisbursementOrder> findByCompany(UUID companyId, int page, int size) {
        return repository.findByCompanyIdOrderByCreatedAtDesc(companyId, PageRequest.of(page, size));
    }
}

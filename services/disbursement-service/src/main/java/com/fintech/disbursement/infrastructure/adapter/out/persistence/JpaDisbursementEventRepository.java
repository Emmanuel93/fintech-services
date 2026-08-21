package com.fintech.disbursement.infrastructure.adapter.out.persistence;

import com.fintech.disbursement.application.port.out.DisbursementEventRepository;
import com.fintech.disbursement.domain.DisbursementEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface JpaDisbursementEventRepository
        extends JpaRepository<DisbursementEvent, UUID>, DisbursementEventRepository {

    @Override
    List<DisbursementEvent> findByDisbursementId(UUID disbursementId);
}

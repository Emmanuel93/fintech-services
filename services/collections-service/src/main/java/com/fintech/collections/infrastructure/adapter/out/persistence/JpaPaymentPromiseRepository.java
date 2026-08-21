package com.fintech.collections.infrastructure.adapter.out.persistence;

import com.fintech.collections.application.port.out.PaymentPromiseRepository;
import com.fintech.collections.domain.PaymentPromise;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JpaPaymentPromiseRepository
        extends JpaRepository<PaymentPromise, UUID>, PaymentPromiseRepository {

    @Override
    @Query("SELECT p FROM PaymentPromise p WHERE p.caseId = :caseId AND p.status = 'ACTIVE'")
    Optional<PaymentPromise> findActiveByCaseId(@Param("caseId") UUID caseId);

    @Override
    List<PaymentPromise> findByCaseId(UUID caseId);

    @Override
    @Query("SELECT p FROM PaymentPromise p WHERE p.status = 'ACTIVE' AND p.promisedDate < :date")
    List<PaymentPromise> findActiveWithPromisedDateBefore(@Param("date") LocalDate date);
}

package com.fintech.collections.infrastructure.adapter.out.persistence;

import com.fintech.collections.application.port.out.WriteOffRecordRepository;
import com.fintech.collections.domain.WriteOffRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface JpaWriteOffRecordRepository
        extends JpaRepository<WriteOffRecord, UUID>, WriteOffRecordRepository {

    @Override
    Optional<WriteOffRecord> findByCreditAccountId(UUID creditAccountId);

    @Override
    boolean existsByCreditAccountId(UUID creditAccountId);
}

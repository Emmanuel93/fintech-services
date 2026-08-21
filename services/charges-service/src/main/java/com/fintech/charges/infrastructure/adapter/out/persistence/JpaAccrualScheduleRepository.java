package com.fintech.charges.infrastructure.adapter.out.persistence;

import com.fintech.charges.application.port.out.AccrualScheduleRepository;
import com.fintech.charges.domain.AccrualSchedule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JpaAccrualScheduleRepository
        extends JpaRepository<AccrualSchedule, UUID>, AccrualScheduleRepository {

    @Override
    Optional<AccrualSchedule> findByCreditAccountId(UUID creditAccountId);

    @Override
    boolean existsByCreditAccountId(UUID creditAccountId);

    @Override
    List<AccrualSchedule> findAllByStatus(String status);

    @Override
    List<AccrualSchedule> findAllByStatusAndMoratoriumActive(String status, boolean moratoriumActive);
}

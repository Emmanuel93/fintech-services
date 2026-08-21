package com.fintech.charges.application.port.out;

import com.fintech.charges.domain.AccrualSchedule;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccrualScheduleRepository {
    Optional<AccrualSchedule> findById(UUID id);
    Optional<AccrualSchedule> findByCreditAccountId(UUID creditAccountId);
    boolean existsByCreditAccountId(UUID creditAccountId);
    List<AccrualSchedule> findAllByStatus(String status);
    List<AccrualSchedule> findAllByStatusAndMoratoriumActive(String status, boolean moratoriumActive);
    AccrualSchedule save(AccrualSchedule schedule);
}

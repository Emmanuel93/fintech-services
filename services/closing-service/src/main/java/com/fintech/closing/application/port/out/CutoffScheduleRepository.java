package com.fintech.closing.application.port.out;

import com.fintech.closing.domain.CutoffSchedule;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CutoffScheduleRepository {
    Optional<CutoffSchedule> find(UUID creditAccountId, int cycleNumber);
    /** La pregunta diaria: ¿a qué cuentas les toca corte hoy? */
    List<CutoffSchedule> findDueOn(LocalDate cutoffDate);
    List<CutoffSchedule> findByAccount(UUID creditAccountId);
    CutoffSchedule save(CutoffSchedule schedule);
    int saveAll(List<CutoffSchedule> schedules);
}

package com.fintech.closing.infrastructure.adapter.out.persistence;

import com.fintech.closing.domain.CutoffSchedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

interface SpringDataCutoffScheduleRepository extends JpaRepository<CutoffSchedule, CutoffSchedule.Key> {

    /** La pregunta diaria del cierre. Sólo los que siguen agendados: un corte sellado no se repite. */
    @Query("SELECT c FROM CutoffSchedule c WHERE c.cutoffDate = :fecha AND c.status = 'SCHEDULED'")
    List<CutoffSchedule> findDueOn(@Param("fecha") LocalDate fecha);

    @Query("SELECT c FROM CutoffSchedule c WHERE c.creditAccountId = :id ORDER BY c.cycleNumber")
    List<CutoffSchedule> findByAccount(@Param("id") UUID creditAccountId);
}

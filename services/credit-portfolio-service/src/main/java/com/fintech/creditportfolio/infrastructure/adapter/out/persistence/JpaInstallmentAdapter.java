package com.fintech.creditportfolio.infrastructure.adapter.out.persistence;

import com.fintech.creditportfolio.application.port.out.InstallmentRepository;
import com.fintech.creditportfolio.domain.Installment;
import com.fintech.creditportfolio.domain.InstallmentStatus;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Repository
class JpaInstallmentAdapter implements InstallmentRepository {

    private final SpringDataInstallmentRepository jpa;

    JpaInstallmentAdapter(SpringDataInstallmentRepository jpa) {
        this.jpa = jpa;
    }

    @Override public void saveAll(List<Installment> items) { jpa.saveAll(items); }
    @Override public List<Installment> findByScheduleIdOrdered(UUID scheduleId) {
        return jpa.findByScheduleIdOrderByInstallmentNumber(scheduleId);
    }
    @Override public List<Installment> findByScheduleIds(java.util.Collection<UUID> scheduleIds) {
        if (scheduleIds == null || scheduleIds.isEmpty()) return List.of();
        return jpa.findByScheduleIdIn(scheduleIds);
    }

    @Override public List<Installment> findByScheduleId(UUID scheduleId) {
        return jpa.findByScheduleIdOrderByInstallmentNumber(scheduleId);
    }
    @Override public List<Installment> findPendingOverdueByScheduleId(UUID scheduleId, LocalDate cutoff) {
        return jpa.findOverdueByScheduleId(scheduleId, InstallmentStatus.PENDING, cutoff);
    }

    @Override public List<Installment> findPendingOverdueByScheduleIds(
            java.util.Collection<UUID> scheduleIds, LocalDate cutoff) {
        if (scheduleIds == null || scheduleIds.isEmpty()) return List.of();
        return jpa.findOverdueByScheduleIds(scheduleIds, InstallmentStatus.PENDING, cutoff);
    }
    @Override public List<Installment> findDueToday(LocalDate today) {
        return jpa.findByDueDateAndStatus(today, InstallmentStatus.PENDING);
    }
    @Override public List<Installment> findDueOnOrBefore(LocalDate date) {
        return jpa.findByDueDateNotAfterAndStatus(date, InstallmentStatus.PENDING);
    }
    @Override public List<Installment> findDueOn(LocalDate date) {
        return jpa.findByDueDateAndStatus(date, InstallmentStatus.PENDING);
    }
    @Override
    public java.util.Map<UUID, com.fintech.creditportfolio.domain.PaymentPlanSummary>
            planSummaries(java.util.Collection<UUID> scheduleIds) {
        if (scheduleIds == null || scheduleIds.isEmpty()) return java.util.Map.of();
        var out = new java.util.HashMap<UUID, com.fintech.creditportfolio.domain.PaymentPlanSummary>();
        for (Object[] r : jpa.planSummariesByScheduleIds(scheduleIds)) {
            out.put((UUID) r[0], new com.fintech.creditportfolio.domain.PaymentPlanSummary(
                    ((Number) r[1]).intValue(),
                    ((Number) r[2]).intValue(),
                    r[3] == null ? null : ((Number) r[3]).intValue(),
                    r[4] == null ? null : ((java.sql.Date) r[4]).toLocalDate(),
                    (java.math.BigDecimal) r[8],   // importe de la mensualidad que toca
                    (java.math.BigDecimal) r[5],
                    (java.math.BigDecimal) r[6],
                    ((Number) r[7]).intValue()));
        }
        return out;
    }

    @Override public void shiftDueDate(UUID scheduleId, int installmentNumber, LocalDate newDueDate) {
        jpa.shiftDueDate(scheduleId, installmentNumber, newDueDate);
    }
}

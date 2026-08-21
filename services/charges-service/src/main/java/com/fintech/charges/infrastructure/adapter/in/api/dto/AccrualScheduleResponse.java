package com.fintech.charges.infrastructure.adapter.in.api.dto;

import com.fintech.charges.domain.AccrualSchedule;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record AccrualScheduleResponse(
        UUID scheduleId,
        UUID creditAccountId,
        String productType,
        String productBehavior,
        String status,
        BigDecimal nominalRate,
        BigDecimal moratoriumRate,
        boolean moratoriumActive,
        LocalDate moratoriumStartDate,
        int gracePeriodDays,
        LocalDate lastAccrualDate,
        BigDecimal principalBalance,
        Instant createdAt
) {
    public static AccrualScheduleResponse from(AccrualSchedule s) {
        return new AccrualScheduleResponse(
                s.getScheduleId(), s.getCreditAccountId(), s.getProductType(),
                s.getProductBehavior(), s.getStatus(), s.getNominalRate(),
                s.getMoratoriumRate(), s.isMoratoriumActive(), s.getMoratoriumStartDate(),
                s.getGracePeriodDays(), s.getLastAccrualDate(),
                s.getPrincipalBalance(), s.getCreatedAt());
    }
}

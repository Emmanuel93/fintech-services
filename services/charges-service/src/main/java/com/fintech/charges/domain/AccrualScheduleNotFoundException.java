package com.fintech.charges.domain;

import com.fintech.shared.exception.DomainException;

public class AccrualScheduleNotFoundException extends DomainException {
    public AccrualScheduleNotFoundException(String creditAccountId) {
        super("CHARGES_SCHEDULE_NOT_FOUND", "AccrualSchedule not found for creditAccountId: " + creditAccountId);
    }
}

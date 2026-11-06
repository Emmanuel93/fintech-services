package com.fintech.creditportfolio.application.service;

import com.fintech.creditportfolio.application.port.out.DayCloseRepository;
import com.fintech.shared.time.BusinessClock;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;

/**
 * The portfolio's date for a payment: the day the money was received, in Mexico City, with no cutoff. A payment received
 * at 23:00 belongs to today and one at 00:05 to tomorrow; that drives due dates, delinquency and the statements.
 *
 * <p>A payment received before midnight and consumed after it still belongs to the day it was received while that day
 * is open (its close runs at 00:05). Once the day is closed its snapshot is final, and a late payment is applied today.
 */
@Component
public class PaymentValueDatePolicy {

    private final DayCloseRepository dayCloses;

    public PaymentValueDatePolicy(DayCloseRepository dayCloses) {
        this.dayCloses = dayCloses;
    }

    /** The value date for a payment received at {@code receivedAt}; {@code null} means today. */
    public LocalDate valueDate(Instant receivedAt) {
        if (receivedAt == null) {
            return null;
        }
        LocalDate received = receivedAt.atZone(BusinessClock.current().zone()).toLocalDate();
        LocalDate today = BusinessClock.current().today();
        if (!received.isBefore(today)) {
            return null;
        }
        return dayCloses.find(received).isEmpty() ? received : null;
    }
}

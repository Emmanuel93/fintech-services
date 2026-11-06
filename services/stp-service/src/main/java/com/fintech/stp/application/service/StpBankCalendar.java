package com.fintech.stp.application.service;

import com.fintech.shared.banking.BankCalendar;
import com.fintech.stp.application.port.out.BusinessCalendarRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Map;

/**
 * The bank calendar as the connector knows it: closing's, copied. Read on every SPEI movement, so the exceptions
 * are cached and the cache is dropped whenever closing publishes a day.
 */
@Service
public class StpBankCalendar implements BankCalendar {

    public static final String MX = "MX";

    private static final Logger log = LoggerFactory.getLogger(StpBankCalendar.class);

    private final BusinessCalendarRepository repository;
    private volatile Map<LocalDate, Boolean> exceptions;

    public StpBankCalendar(BusinessCalendarRepository repository) {
        this.repository = repository;
    }

    @Override
    public boolean isBusinessDay(LocalDate day) {
        Boolean exception = exceptions().get(day);
        return exception != null ? exception : BankCalendar.isWeekday(day);
    }

    /** A day as closing published it. Only the {@code MX} calendar dates SPEI movements. */
    public void record(String calendarCode, LocalDate day, boolean business, String label) {
        repository.upsert(calendarCode, day, business, label);
        if (MX.equals(calendarCode)) {
            exceptions = null;
            log.info("Calendario bancario: {} queda {} ({})", day, business ? "hábil" : "inhábil", label);
        }
    }

    private Map<LocalDate, Boolean> exceptions() {
        Map<LocalDate, Boolean> current = exceptions;
        if (current == null) {
            current = Map.copyOf(repository.exceptions(MX));
            exceptions = current;
        }
        return current;
    }
}

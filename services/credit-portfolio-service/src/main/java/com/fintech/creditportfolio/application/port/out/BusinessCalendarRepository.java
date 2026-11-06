package com.fintech.creditportfolio.application.port.out;

import java.time.LocalDate;
import java.util.Map;

/** The business calendar's exceptions as closing published them: day → whether banks operate. */
public interface BusinessCalendarRepository {

    void upsert(String calendarCode, LocalDate day, boolean business, String label);

    Map<LocalDate, Boolean> exceptions(String calendarCode);
}

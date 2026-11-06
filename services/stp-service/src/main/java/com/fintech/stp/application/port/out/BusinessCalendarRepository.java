package com.fintech.stp.application.port.out;

import java.time.LocalDate;
import java.util.Map;

/** The connector's copy of closing's bank calendar: only the days that differ from the Monday-to-Friday rule. */
public interface BusinessCalendarRepository {

    /** Every exception of {@code calendarCode}: day → whether banks operate on it. */
    Map<LocalDate, Boolean> exceptions(String calendarCode);

    /** Records a day as closing published it; a later publication of the same day replaces it. */
    void upsert(String calendarCode, LocalDate day, boolean business, String label);
}

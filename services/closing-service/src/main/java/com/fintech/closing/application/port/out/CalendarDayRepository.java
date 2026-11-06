package com.fintech.closing.application.port.out;

import com.fintech.closing.domain.CalendarDay;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface CalendarDayRepository {
    Optional<CalendarDay> find(String calendarCode, LocalDate day);
    CalendarDay save(CalendarDay day);

    /** Every exception of a calendar (the days that differ from the weekday rule), oldest first. */
    List<CalendarDay> findAll(String calendarCode);
}

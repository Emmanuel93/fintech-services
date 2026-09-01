package com.fintech.closing.application.port.out;

import com.fintech.closing.domain.CalendarDay;

import java.time.LocalDate;
import java.util.Optional;

public interface CalendarDayRepository {
    Optional<CalendarDay> find(String calendarCode, LocalDate day);
    CalendarDay save(CalendarDay day);
}

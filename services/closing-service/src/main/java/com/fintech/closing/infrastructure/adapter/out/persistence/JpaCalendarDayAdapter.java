package com.fintech.closing.infrastructure.adapter.out.persistence;

import com.fintech.closing.application.port.out.CalendarDayRepository;
import com.fintech.closing.domain.CalendarDay;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Optional;

@Repository
class JpaCalendarDayAdapter implements CalendarDayRepository {

    private final SpringDataCalendarDayRepository jpa;

    JpaCalendarDayAdapter(SpringDataCalendarDayRepository jpa) { this.jpa = jpa; }

    @Override
    public Optional<CalendarDay> find(String calendarCode, LocalDate day) {
        return jpa.findById(new CalendarDay.Key(calendarCode, day));
    }

    @Override
    public CalendarDay save(CalendarDay day) { return jpa.save(day); }
}

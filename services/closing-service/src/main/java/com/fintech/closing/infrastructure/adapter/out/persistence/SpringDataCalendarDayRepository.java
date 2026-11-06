package com.fintech.closing.infrastructure.adapter.out.persistence;

import com.fintech.closing.domain.CalendarDay;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

interface SpringDataCalendarDayRepository extends JpaRepository<CalendarDay, CalendarDay.Key> {
    List<CalendarDay> findByCalendarCodeOrderByDayAsc(String calendarCode);
}

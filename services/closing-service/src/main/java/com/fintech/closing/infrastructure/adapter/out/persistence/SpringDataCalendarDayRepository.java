package com.fintech.closing.infrastructure.adapter.out.persistence;

import com.fintech.closing.domain.CalendarDay;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataCalendarDayRepository extends JpaRepository<CalendarDay, CalendarDay.Key> {
}

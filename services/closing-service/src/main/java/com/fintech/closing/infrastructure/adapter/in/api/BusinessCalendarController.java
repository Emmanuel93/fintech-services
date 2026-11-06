package com.fintech.closing.infrastructure.adapter.in.api;

import com.fintech.closing.application.port.out.CalendarDayRepository;
import com.fintech.closing.application.service.BusinessCalendarPublisher;
import com.fintech.closing.application.service.BusinessCalendarService;
import com.fintech.closing.domain.CalendarDay;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * The business calendar: which days banks operate. Reading it is open to any authenticated caller (the chain and
 * the tools ask for the next business day); changing a day is the administrator's.
 */
@RestController
@RequestMapping("/api/v1/calendars/{calendarCode}")
class BusinessCalendarController {

    private final CalendarDayRepository days;
    private final BusinessCalendarService calendar;
    private final BusinessCalendarPublisher publisher;

    BusinessCalendarController(CalendarDayRepository days, BusinessCalendarService calendar,
                               BusinessCalendarPublisher publisher) {
        this.days = days;
        this.calendar = calendar;
        this.publisher = publisher;
    }

    record DayView(LocalDate day, boolean business, String label) {}

    record DayInfo(LocalDate day, boolean business, LocalDate onOrAfter, LocalDate nextBusinessDay) {}

    record DayRequest(boolean business, String label) {}

    /** The days that differ from the Monday-to-Friday rule (the holidays). */
    @GetMapping("/days")
    List<DayView> days(@PathVariable String calendarCode) {
        return days.findAll(calendarCode).stream().map(d -> new DayView(d.getDay(), d.isBusiness(), d.getLabel())).toList();
    }

    /** Whether a day is a business day, the first business day on or after it, and the next one after it. */
    @GetMapping("/business-day")
    DayInfo businessDay(@PathVariable String calendarCode,
                        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day) {
        boolean business = calendar.isBusinessDay(calendarCode, day);
        return new DayInfo(day, business, business ? day : calendar.nextBusinessDay(calendarCode, day),
                calendar.nextBusinessDay(calendarCode, day));
    }

    @PutMapping("/days/{day}")
    DayView set(@PathVariable String calendarCode, @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day,
                @RequestBody DayRequest body) {
        CalendarDay d = publisher.set(calendarCode, day, body.business(), body.label());
        return new DayView(d.getDay(), d.isBusiness(), d.getLabel());
    }
}

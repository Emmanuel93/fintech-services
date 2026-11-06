package com.fintech.stp.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fintech.stp.application.service.StpBankCalendar;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/** The bank calendar as closing publishes it ({@code closing.business-calendar-day}). */
@Component
public class BusinessCalendarDayListener {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CalendarDayPayload(String calendarCode, LocalDate day, boolean business, String label) {}

    private final StpBankCalendar calendar;

    public BusinessCalendarDayListener(StpBankCalendar calendar) {
        this.calendar = calendar;
    }

    @KafkaListener(topics = "closing.business-calendar-day",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "businessCalendarDayListenerContainerFactory")
    public void onMessage(CalendarDayPayload day) {
        if (day.calendarCode() == null || day.day() == null) {
            return;
        }
        calendar.record(day.calendarCode(), day.day(), day.business(), day.label());
    }
}

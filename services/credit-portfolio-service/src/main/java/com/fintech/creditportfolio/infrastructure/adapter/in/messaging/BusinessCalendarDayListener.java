package com.fintech.creditportfolio.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fintech.creditportfolio.application.service.BusinessCalendarSync;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/** The business calendar as closing publishes it ({@code closing.business-calendar-day}). */
@Component
public class BusinessCalendarDayListener {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CalendarDayPayload(String calendarCode, LocalDate day, boolean business, String label) {}

    private final BusinessCalendarSync sync;

    public BusinessCalendarDayListener(BusinessCalendarSync sync) {
        this.sync = sync;
    }

    @KafkaListener(topics = "closing.business-calendar-day",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "businessCalendarDayListenerContainerFactory")
    public void onMessage(CalendarDayPayload p) {
        if (p.calendarCode() == null || p.day() == null) return;
        sync.onDay(p.calendarCode(), p.day(), p.business(), p.label());
    }
}

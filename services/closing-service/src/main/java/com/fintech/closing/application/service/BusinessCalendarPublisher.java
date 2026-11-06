package com.fintech.closing.application.service;

import com.fintech.closing.application.port.out.CalendarDayRepository;
import com.fintech.closing.domain.CalendarDay;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * The business calendar for the rest of the platform. Closing owns it (the cutoffs and due dates are computed here);
 * the portfolio needs it to date the installments of a loan on business days, and keeps a copy fed by
 * {@code closing.business-calendar-day}. Every day of the calendar is published when the service starts (a consumer
 * that starts empty gets the whole calendar) and again whenever one changes.
 */
@Service
public class BusinessCalendarPublisher {

    private static final Logger log = LoggerFactory.getLogger(BusinessCalendarPublisher.class);
    public static final String TOPIC = "closing.business-calendar-day";
    public static final String CALENDAR = "MX";

    private final CalendarDayRepository days;
    private final KafkaTemplate<String, Object> kafka;

    public BusinessCalendarPublisher(CalendarDayRepository days, KafkaTemplate<String, Object> kafka) {
        this.days = days;
        this.kafka = kafka;
    }

    public record CalendarDayMessage(String calendarCode, LocalDate day, boolean business, String label) {}

    /**
     * In the background and never fatal: a broker that is not there yet must not keep closing from starting. If it
     * fails, the calendar goes out on the next start or with the next change, and the consumers keep the copy they had.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void publishAllOnStart() {
        CompletableFuture.runAsync(() -> {
            try {
                List<CalendarDay> all = days.findAll(CALENDAR);
                all.forEach(this::publish);
                log.info("Calendario {} publicado: {} días que difieren de la regla de lunes a viernes", CALENDAR, all.size());
            } catch (RuntimeException e) {
                log.warn("No se pudo publicar el calendario {} al arrancar: {}", CALENDAR, e.getMessage());
            }
        });
    }

    /** Sets whether {@code day} is a business day (a holiday decreed, or one moved) and tells the platform. */
    @Transactional
    public CalendarDay set(String calendarCode, LocalDate day, boolean business, String label) {
        CalendarDay saved = days.save(CalendarDay.of(calendarCode, day, business, label));
        publish(saved);
        log.info("Calendario {}: {} queda {} ({})", calendarCode, day, business ? "hábil" : "inhábil", label);
        return saved;
    }

    private void publish(CalendarDay d) {
        kafka.send(TOPIC, d.getCalendarCode() + ":" + d.getDay(),
                new CalendarDayMessage(d.getCalendarCode(), d.getDay(), d.isBusiness(), d.getLabel()));
    }
}

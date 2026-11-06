package com.fintech.creditportfolio.application.service;

import com.fintech.creditportfolio.application.port.out.BusinessCalendarRepository;
import com.fintech.creditportfolio.domain.Installment;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * The days banks operate in Mexico: Monday to Friday, except the bank holidays the CNBV publishes (and any day
 * decreed otherwise), as closing publishes them. A loan's installment that falls due on a weekend or a holiday is due
 * on the next business day (the «following» convention): the client pays then without being late, without moratorium
 * and without a collections call. The installment keeps its contractual date as its original due date.
 */
@Service
public class BankingCalendar {

    public static final String MX = "MX";

    private final BusinessCalendarRepository repository;
    private volatile Map<LocalDate, Boolean> exceptions;

    public BankingCalendar(BusinessCalendarRepository repository) {
        this.repository = repository;
    }

    /** The calendar before closing publishes any holiday: only weekends are non-business days. */
    public static BankingCalendar weekendsOnly() {
        return new BankingCalendar(new BusinessCalendarRepository() {
            @Override public void upsert(String calendarCode, LocalDate day, boolean business, String label) { }
            @Override public Map<LocalDate, Boolean> exceptions(String calendarCode) { return Map.of(); }
        });
    }

    public boolean isBusinessDay(LocalDate day) {
        Boolean exception = exceptions().get(day);
        if (exception != null) return exception;
        return day.getDayOfWeek() != DayOfWeek.SATURDAY && day.getDayOfWeek() != DayOfWeek.SUNDAY;
    }

    /** {@code day} if banks operate on it, else the next day they do. */
    public LocalDate onOrAfter(LocalDate day) {
        LocalDate d = day;
        for (int i = 0; i < 31 && !isBusinessDay(d); i++) d = d.plusDays(1);
        return d;
    }

    /** Moves every installment due on a non-business day to the next business day; returns how many moved. */
    public int followBusinessDays(List<Installment> plan) {
        int moved = 0;
        for (Installment i : plan) {
            LocalDate due = onOrAfter(i.getDueDate());
            if (!due.equals(i.getDueDate())) {
                i.shiftDueDate(due);
                moved++;
            }
        }
        return moved;
    }

    /** Records a day as closing published it and forgets the cached calendar. */
    public void record(String calendarCode, LocalDate day, boolean business, String label) {
        repository.upsert(calendarCode, day, business, label);
        exceptions = null;
    }

    private Map<LocalDate, Boolean> exceptions() {
        Map<LocalDate, Boolean> e = exceptions;
        if (e == null) {
            e = Map.copyOf(repository.exceptions(MX));
            exceptions = e;
        }
        return e;
    }
}

package com.fintech.creditportfolio.calendar;

import com.fintech.creditportfolio.application.port.out.BusinessCalendarRepository;
import com.fintech.creditportfolio.application.service.BankingCalendar;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The bank holidays of 2026 to 2029 as closing publishes them (the CNBV's calendar): New Year, the Constitution's and
 * Juárez's Mondays, Holy Thursday and Friday, Labour Day, Independence Day, the Day of the Dead, the Revolution's
 * Monday, the bank employees' day and Christmas.
 */
public final class MexicanBankHolidays {

    public static final List<LocalDate> DAYS = Stream.of(
            "2026-01-01", "2026-02-02", "2026-03-16", "2026-04-02", "2026-04-03", "2026-05-01",
            "2026-09-16", "2026-11-02", "2026-11-16", "2026-12-12", "2026-12-25",
            "2027-01-01", "2027-02-01", "2027-03-15", "2027-03-25", "2027-03-26", "2027-05-01",
            "2027-09-16", "2027-11-02", "2027-11-15", "2027-12-12", "2027-12-25",
            "2028-01-01", "2028-02-07", "2028-03-20", "2028-04-13", "2028-04-14", "2028-05-01",
            "2028-09-16", "2028-11-02", "2028-11-20", "2028-12-12", "2028-12-25",
            "2029-01-01", "2029-02-05", "2029-03-19", "2029-03-29", "2029-03-30", "2029-05-01",
            "2029-09-16", "2029-11-02", "2029-11-19", "2029-12-12", "2029-12-25")
            .map(LocalDate::parse).toList();

    private MexicanBankHolidays() { }

    /** A calendar with these holidays published. */
    public static BankingCalendar calendar() {
        Map<LocalDate, Boolean> days = new HashMap<>();
        DAYS.forEach(d -> days.put(d, false));
        return new BankingCalendar(new BusinessCalendarRepository() {
            @Override public void upsert(String calendarCode, LocalDate day, boolean business, String label) {
                days.put(day, business);
            }
            @Override public Map<LocalDate, Boolean> exceptions(String calendarCode) { return Map.copyOf(days); }
        });
    }
}

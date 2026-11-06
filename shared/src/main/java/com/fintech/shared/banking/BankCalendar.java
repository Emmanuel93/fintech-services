package com.fintech.shared.banking;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Set;

/**
 * The days banks operate in Mexico: Monday to Friday except the bank holidays the CNBV publishes, as closing owns
 * and publishes them ({@code closing.business-calendar-day}).
 *
 * <p>A port, not a table: each service that needs it backs it with its own copy of closing's calendar, and a test
 * backs it with a fixed set of holidays. Pure Java, no Spring.
 */
@FunctionalInterface
public interface BankCalendar {

    /** No calendar runs more than this many non-business days in a row; past it something is wrong with the data. */
    int MAX_CONSECUTIVE_NON_BUSINESS_DAYS = 31;

    boolean isBusinessDay(LocalDate day);

    /** {@code day} if banks operate on it, else the next day they do. */
    default LocalDate onOrAfter(LocalDate day) {
        LocalDate d = Objects.requireNonNull(day, "day");
        for (int i = 0; i < MAX_CONSECUTIVE_NON_BUSINESS_DAYS; i++) {
            if (isBusinessDay(d)) {
                return d;
            }
            d = d.plusDays(1);
        }
        throw new IllegalStateException("El calendario bancario no tiene un día hábil en "
                + MAX_CONSECUTIVE_NON_BUSINESS_DAYS + " días a partir del " + day);
    }

    /** The first business day strictly after {@code day}. */
    default LocalDate nextAfter(LocalDate day) {
        return onOrAfter(Objects.requireNonNull(day, "day").plusDays(1));
    }

    /** Monday to Friday, every one of them a business day: the rule a calendar applies when it has no exception. */
    static boolean isWeekday(LocalDate day) {
        DayOfWeek dow = day.getDayOfWeek();
        return dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY;
    }

    /** Weekdays except the given holidays. For tests and for seeding; services read their copy of closing's. */
    static BankCalendar weekdaysExcept(Set<LocalDate> holidays) {
        Set<LocalDate> copy = Set.copyOf(holidays);
        return day -> isWeekday(day) && !copy.contains(day);
    }
}

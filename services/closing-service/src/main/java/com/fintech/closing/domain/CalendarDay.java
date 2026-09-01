package com.fintech.closing.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Un día del calendario de negocio y si se opera en él.
 *
 * <p>Se guardan los días uno por uno en vez de derivarlos de una regla. Es deliberado: los días
 * inhábiles bancarios en México no salen de una fórmula estable —hay traslados por decreto— y un
 * algoritmo que acierta este año y falla el siguiente es peor que una tabla, porque nadie lo revisa.
 */
@Entity
@Table(name = "calendar_days", schema = "closing")
@IdClass(CalendarDay.Key.class)
public class CalendarDay {

    @Id
    @Column(name = "calendar_code", nullable = false, length = 20)
    private String calendarCode;

    @Id
    @Column(name = "day", nullable = false)
    private LocalDate day;

    @Column(name = "is_business", nullable = false)
    private boolean business;

    @Column(name = "label", length = 80)
    private String label;

    protected CalendarDay() {}

    public static CalendarDay of(String calendarCode, LocalDate day, boolean business, String label) {
        CalendarDay d = new CalendarDay();
        d.calendarCode = calendarCode;
        d.day          = day;
        d.business     = business;
        d.label        = label;
        return d;
    }

    public String getCalendarCode() { return calendarCode; }
    public LocalDate getDay()       { return day; }
    public boolean isBusiness()     { return business; }
    public String getLabel()        { return label; }

    public static class Key implements Serializable {
        private String calendarCode;
        private LocalDate day;

        public Key() {}
        public Key(String calendarCode, LocalDate day) {
            this.calendarCode = calendarCode; this.day = day;
        }

        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key k)) return false;
            return Objects.equals(calendarCode, k.calendarCode) && Objects.equals(day, k.day);
        }
        @Override public int hashCode() { return Objects.hash(calendarCode, day); }
    }
}

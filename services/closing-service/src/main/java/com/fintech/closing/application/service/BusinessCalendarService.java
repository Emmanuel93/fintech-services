package com.fintech.closing.application.service;

import com.fintech.closing.application.port.out.CalendarDayRepository;
import com.fintech.closing.domain.NonBusinessDayShift;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;

/**
 * La fecha de negocio, que sustituye a {@code LocalDate.now()} dentro de cada job.
 *
 * <p>Hoy la fecha de un cierre es el reloj del pod que lo corre. Eso impide tres cosas a la vez:
 * saber si un día es hábil, correr un corte que cae en domingo, y —la que más duele— <b>reproducir
 * el cierre del día 15 el día 20</b>, que es lo que exige cualquier reproceso o siembra de historia.
 */
@Service
public class BusinessCalendarService {

    private final CalendarDayRepository calendarDays;

    public BusinessCalendarService(CalendarDayRepository calendarDays) {
        this.calendarDays = calendarDays;
    }

    /**
     * Si se opera ese día.
     *
     * <p>La tabla sólo guarda excepciones. Lo que no esté registrado se resuelve por la regla
     * general —de lunes a viernes se opera— y así un calendario nuevo funciona desde el primer día
     * sin tener que cargarle los 365 días del año antes de poder cerrar nada.
     */
    @Transactional(readOnly = true)
    public boolean isBusinessDay(String calendarCode, LocalDate day) {
        return calendarDays.find(calendarCode, day)
                .map(d -> d.isBusiness())
                .orElseGet(() -> day.getDayOfWeek() != DayOfWeek.SATURDAY
                              && day.getDayOfWeek() != DayOfWeek.SUNDAY);
    }

    @Transactional(readOnly = true)
    public LocalDate nextBusinessDay(String calendarCode, LocalDate from) {
        LocalDate d = from.plusDays(1);
        for (int i = 0; i < 31 && !isBusinessDay(calendarCode, d); i++) {
            d = d.plusDays(1);
        }
        return d;
    }

    @Transactional(readOnly = true)
    public LocalDate previousBusinessDay(String calendarCode, LocalDate from) {
        LocalDate d = from.minusDays(1);
        for (int i = 0; i < 31 && !isBusinessDay(calendarCode, d); i++) {
            d = d.minusDays(1);
        }
        return d;
    }

    /**
     * Corre una fecha que cayó en inhábil, según la política del producto.
     *
     * <p>Si ya es hábil, se devuelve tal cual — la regla sólo actúa cuando hace falta.
     */
    @Transactional(readOnly = true)
    public LocalDate shift(String calendarCode, LocalDate date, NonBusinessDayShift rule) {
        if (rule == NonBusinessDayShift.NONE || isBusinessDay(calendarCode, date)) {
            return date;
        }
        return switch (rule) {
            case NEXT -> nextBusinessDayInclusive(calendarCode, date);
            case PREV -> previousBusinessDayInclusive(calendarCode, date);
            case NONE -> date;
        };
    }

    private LocalDate nextBusinessDayInclusive(String calendarCode, LocalDate from) {
        LocalDate d = from;
        for (int i = 0; i < 31 && !isBusinessDay(calendarCode, d); i++) d = d.plusDays(1);
        return d;
    }

    private LocalDate previousBusinessDayInclusive(String calendarCode, LocalDate from) {
        LocalDate d = from;
        for (int i = 0; i < 31 && !isBusinessDay(calendarCode, d); i++) d = d.minusDays(1);
        return d;
    }

    /** Cuántos días hábiles hay entre dos fechas, sin contar la de inicio. */
    @Transactional(readOnly = true)
    public int businessDaysBetween(String calendarCode, LocalDate from, LocalDate to) {
        int n = 0;
        for (LocalDate d = from.plusDays(1); !d.isAfter(to); d = d.plusDays(1)) {
            if (isBusinessDay(calendarCode, d)) n++;
        }
        return n;
    }

    /** Suma días hábiles. Es lo que usa la fecha límite de pago cuando el offset es en hábiles. */
    @Transactional(readOnly = true)
    public LocalDate plusBusinessDays(String calendarCode, LocalDate from, int days) {
        LocalDate d = from;
        for (int i = 0; i < days; i++) d = nextBusinessDay(calendarCode, d);
        return d;
    }
}

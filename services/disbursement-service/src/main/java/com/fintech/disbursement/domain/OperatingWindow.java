package com.fintech.disbursement.domain;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Set;

/**
 * Ventana operativa del rail.
 *
 * <p>Es <strong>envolvente</strong>: {@code start > end} significa que abre a las 17:10 de un día y
 * cierra a las 16:50 del siguiente. La franja cerrada es la corta (16:50–17:10), que es la que el
 * legado apagaba con dos crons y cuatro réplicas descoordinadas.
 *
 * <p>Fuera de ventana la orden queda {@code REQUESTED} con {@code scheduledFor}; no se rechaza
 * (DB-05). El estado vive en la base de datos, así que reiniciar un pod no cambia nada.
 */
public record OperatingWindow(LocalTime start, LocalTime end, ZoneId zone, Set<DayOfWeek> days) {

    /** {@code start == end} significa 24 h: es como se configura un rail sin corte (p. ej. INTERNAL). */
    public boolean isAlwaysOpen() {
        return start.equals(end) && days.size() == DayOfWeek.values().length;
    }

    public boolean isOpenAt(ZonedDateTime moment) {
        if (isAlwaysOpen()) {
            return true;
        }
        ZonedDateTime local = moment.withZoneSameInstant(zone);
        if (!days.contains(local.getDayOfWeek())) {
            return false;
        }
        LocalTime time = local.toLocalTime();
        return start.equals(end) || start.isAfter(end)
                ? !time.isBefore(start) || time.isBefore(end)   // envolvente
                : !time.isBefore(start) && time.isBefore(end);
    }

    /** Cuándo vuelve a abrir. Es lo que se guarda en {@code scheduledFor}. */
    public ZonedDateTime nextOpening(ZonedDateTime from) {
        if (isAlwaysOpen()) {
            return from;
        }
        ZonedDateTime candidate = from.withZoneSameInstant(zone)
                .withHour(start.getHour()).withMinute(start.getMinute())
                .withSecond(0).withNano(0);
        if (!candidate.isAfter(from.withZoneSameInstant(zone))) {
            candidate = candidate.plusDays(1);
        }
        int guard = 0;
        while (!days.contains(candidate.getDayOfWeek()) && guard++ < 7) {
            candidate = candidate.plusDays(1);
        }
        return candidate;
    }
}

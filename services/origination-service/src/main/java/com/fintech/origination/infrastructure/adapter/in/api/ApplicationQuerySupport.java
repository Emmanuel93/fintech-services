package com.fintech.origination.infrastructure.adapter.in.api;

import org.springframework.data.domain.Sort;

import com.fintech.shared.time.BusinessClock;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;

/**
 * Utilidades compartidas por las dos bandejas de solicitudes (general y de
 * underwriting): orden con whitelist y traducción del rango de fechas a límites
 * instantáneos ({@code to} exclusivo).
 */
final class ApplicationQuerySupport {

    /** Tope de página: un cliente no puede pedir la bandeja entera en una llamada. */
    static final int MAX_PAGE_SIZE = 100;

    /** Campos por los que se puede ordenar (whitelist: el `sort` viene del cliente). */
    private static final Set<String> SORTABLE =
            Set.of("createdAt", "updatedAt", "status", "productType", "requestedAmount");

    private ApplicationQuerySupport() {}

    /** {@code sort=campo[,asc|desc]} → {@link Sort} seguro; por defecto, más reciente primero. */
    static Sort parseSort(String sort) {
        Sort defaultSort = Sort.by(Sort.Direction.DESC, "createdAt");
        if (sort == null || sort.isBlank()) {
            return defaultSort;
        }
        String[] parts = sort.split(",");
        String field = parts[0].trim();
        if (!SORTABLE.contains(field)) {
            return defaultSort;
        }
        Sort.Direction dir = parts.length > 1 && parts[1].trim().equalsIgnoreCase("asc")
                ? Sort.Direction.ASC
                : Sort.Direction.DESC;
        return Sort.by(dir, field);
    }

    /**
     * Inicio del día de negocio como límite inferior inclusivo; null si no hay fecha.
     *
     * <p>«Del 30» es el 30 en México: con el día en UTC, una solicitud de las 19:00 quedaba fuera
     * del filtro de su propio día y dentro del siguiente.
     */
    static Instant startOfDay(LocalDate date) {
        return date == null ? null : date.atStartOfDay(BusinessClock.current().zone()).toInstant();
    }

    /** Inicio del día de negocio siguiente como límite superior EXCLUSIVO; null si no hay fecha. */
    static Instant startOfNextDay(LocalDate date) {
        return date == null ? null : date.plusDays(1).atStartOfDay(BusinessClock.current().zone()).toInstant();
    }
}

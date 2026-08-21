package com.fintech.collections.infrastructure.adapter.in.api.dto;

import java.util.List;

/**
 * Los contactos del caso junto al cupo de hoy.
 *
 * <p>El conteo viaja con la lista y no como endpoint aparte porque la pantalla los necesita a la
 * vez: pintar el historial y, encima, «2 de 3 intentos hoy». Separarlos obligaría a dos llamadas
 * que sólo pueden leerse juntas, y a que el cliente reste fechas para recomponer el cupo —con otra
 * zona horaria que la del servidor, que es donde se decide.
 */
public record ContactAttemptsResponse(
        List<ContactAttemptResponse> attempts,
        long todayCount,
        int maxPerDay
) {}

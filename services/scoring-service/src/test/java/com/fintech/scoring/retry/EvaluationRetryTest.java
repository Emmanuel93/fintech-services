package com.fintech.scoring.retry;

import com.fintech.scoring.application.EvaluationNotReady;
import com.fintech.scoring.application.BureauReportPending;
import com.fintech.scoring.application.service.ScoringEvaluationService;
import com.fintech.scoring.infrastructure.adapter.in.messaging.ScoreRequestedEventListener;
import com.fintech.scoring.infrastructure.adapter.in.messaging.ScoreRequestedPayload;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * El reintento distingue la carrera del fallo.
 *
 * <p>Antes había un {@code for} con {@code Thread.sleep(600)} dentro del listener: cinco intentos
 * fijos para todo. Dos problemas — dormir ahí <b>retiene la partición</b>, así que el camino feliz
 * de las demás solicitudes paga la espera de ésta; y reintentaba por igual dos cosas opuestas:
 *
 * <ul>
 *   <li>el prefetch <b>va en camino</b> — es una carrera entre el evento de puntaje y el reporte, y
 *       esperar la resuelve;</li>
 *   <li>el prefetch <b>terminó en FAILED</b> hace horas — esperar no lo resucita, y reintentar sólo
 *       retrasa el momento en que alguien se entera.</li>
 * </ul>
 *
 * <p>Se prueba con un contexto mínimo y no con mocks sueltos porque lo que se verifica es el
 * <b>proxy</b> de Spring Retry: invocar el método a pelo saltaría exactamente lo que hay que probar.
 */
@SpringJUnitConfig
@EnableRetry
class EvaluationRetryTest {

    @TestConfiguration
    static class Config {
        @Bean
        ScoreRequestedEventListener listener(ScoringEvaluationService servicio) {
            return new ScoreRequestedEventListener(servicio);
        }
    }

    @Autowired ScoreRequestedEventListener listener;
    @MockitoBean ScoringEvaluationService servicio;

    private static ScoreRequestedPayload request() {
        return new ScoreRequestedPayload(UUID.randomUUID(), UUID.randomUUID(),
                "INDIVIDUAL", "PERSONAL_LOAN", new java.math.BigDecimal("50000"), 12, "evt-1");
    }

    @Test
    @DisplayName("🔑 La carrera SÍ se reintenta: el reporte va en camino y llega")
    void la_carrera_se_reintenta() {
        given(servicio.evaluateForApplication(any(), any(), any(), any()))
                .willThrow(new BureauReportPending("va en camino"))
                .willThrow(new BureauReportPending("va en camino"))
                .willReturn(java.util.Optional.empty());

        listener.onScoreRequested(request());

        // Tres llamadas: dos que encontraron la carrera y una que ya vio el reporte.
        verify(servicio, times(3)).evaluateForApplication(any(), any(), any(), any());
    }

    @Test
    @DisplayName("🔑 El fallo NO se reintenta: esperar no resucita un prefetch muerto")
    void el_fallo_no_se_reintenta() {
        willThrow(new EvaluationNotReady(
                EvaluationNotReady.Reason.PREFETCH_FAILED, "terminó en FAILED"))
                .given(servicio).evaluateForApplication(any(), any(), any(), any());

        listener.onScoreRequested(request());

        // Una sola. Reintentar lo que no va a cambiar sólo retrasa que alguien se entere.
        verify(servicio, times(1)).evaluateForApplication(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Sin política activa tampoco: es configuración, y no aparece sola")
    void la_falta_de_politica_no_se_reintenta() {
        willThrow(new EvaluationNotReady(
                EvaluationNotReady.Reason.NO_ACTIVE_POLICY, "sin política"))
                .given(servicio).evaluateForApplication(any(), any(), any(), any());

        listener.onScoreRequested(request());

        verify(servicio, times(1)).evaluateForApplication(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Agotada la carrera, el mensaje se consume y queda pendiente de intervención")
    void agotada_la_carrera_queda_pendiente() {
        willThrow(new BureauReportPending("nunca llegó"))
                .given(servicio).evaluateForApplication(any(), any(), any(), any());

        // No propaga: devolver el mensaje a la cola lo haría rebotar sobre un estado que no
        // cambia, bloqueando la partición para las solicitudes que sí se pueden resolver.
        listener.onScoreRequested(request());

        verify(servicio, times(5)).evaluateForApplication(any(), any(), any(), any());
    }
}

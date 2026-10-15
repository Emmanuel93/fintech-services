package com.fintech.scoring.infrastructure.adapter.in.messaging;

import com.fintech.scoring.application.service.ScoringEvaluationService;
import com.fintech.scoring.domain.ScoreEvaluation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.fintech.scoring.application.EvaluationNotReady;
import com.fintech.scoring.application.BureauReportPending;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Phase D — runs the scoring DECISION ENGINE when a product is selected
 * (origination.score-requested), reusing the bureau report prefetched at onboarding.
 * Emits ScoringCompleted carrying the applicationId so origination can correlate directly.
 *
 * <p>Reintento acotado: el prefetch del buró (disparado por {@code prospect-created}) corre async
 * y puede no haber persistido el reporte cuando llega el {@code score-requested} (el usuario elige
 * producto muy rápido tras registrarse). Cada intento es una llamada cross-bean → transacción
 * {@code REQUIRES_NEW} fresca que re-lee el reporte ya commiteado por el prefetch.
 */
@Component
public class ScoreRequestedEventListener {

    private static final Logger log = LoggerFactory.getLogger(ScoreRequestedEventListener.class);

    private final ScoringEvaluationService scoringEvaluationService;

    public ScoreRequestedEventListener(
            ScoringEvaluationService scoringEvaluationService) {
        this.scoringEvaluationService = scoringEvaluationService;
    }

    @KafkaListener(
            topics = "origination.score-requested",
            groupId = "scoring-service",
            containerFactory = "scoreRequestedListenerContainerFactory")
    /**
     * Reintenta <b>sólo la carrera</b>: el evento de puntaje llegó antes que el reporte de buró.
     *
     * <p>Spring Retry y no {@code Thread.sleep}: dormir dentro del listener retiene la partición
     * mientras espera, así que el camino feliz de todas las demás solicitudes paga la espera de
     * ésta. Antes eran cinco intentos de 600 ms fijos — tres segundos de partición bloqueada.
     *
     * <p><b>La anotación va aquí y no en un método interno.</b> Un {@code @Retryable} invocado desde
     * otro método de esta misma clase no se aplica: la llamada va sobre {@code this} y se salta el
     * proxy de Spring, de modo que el reintento existiría en el código y no en la ejecución. Lo
     * destapó la prueba del proxy; llamando al método a pelo no se habría visto.
     *
     * <p>Y reintenta <b>sólo</b> {@link BureauReportPending}. Un prefetch que terminó en `FAILED` no va
     * a aparecer porque volvamos a mirar. La reintentabilidad vive en el <b>tipo</b> y no en una
     * expresión SpEL: aquélla ataba el reintento al nombre del bean, así que renombrar la clase lo
     * habría roto en ejecución sin que nada dejara de compilar.
     */
    @Retryable(
            retryFor = BureauReportPending.class,
            maxAttemptsExpression = "${fintech.scoring.evaluation-retry-attempts:5}",
            backoff = @Backoff(
                    delayExpression = "${fintech.scoring.evaluation-retry-backoff-ms:600}",
                    multiplier = 2.0,
                    maxDelayExpression = "${fintech.scoring.evaluation-retry-max-backoff-ms:10000}"))
    public void onScoreRequested(@Payload ScoreRequestedPayload payload) {
        log.info("Received ScoreRequested applicationId={} prospectId={} prospectType={} productType={}",
                payload.applicationId(), payload.prospectId(), payload.prospectType(), payload.productType());
        scoringEvaluationService.evaluateForApplication(
                payload.applicationId(), payload.prospectId(),
                payload.prospectType(), payload.productType());
    }

    /**
     * Reintenta <b>sólo la carrera</b>: el evento de puntaje llegó antes que el reporte de buró.
     *
     * <p>Spring Retry y no {@code Thread.sleep}: dormir dentro del listener retiene la partición
     * mientras espera, así que el camino feliz de todas las demás solicitudes paga la espera de
     * ésta. Antes eran cinco intentos de 600 ms fijos — tres segundos de partición bloqueada por
     * solicitud.
     *
     * <p>Y reintenta <b>sólo</b> {@code BUREAU_REPORT_PENDING}. Un prefetch que terminó en `FAILED` no
     * va a aparecer porque volvamos a mirar: reintentar eso sólo retrasa el momento en que alguien
     * se entera. `noRetryFor` no sirve aquí porque el motivo viaja dentro de la misma excepción, así
     * que la decisión se toma en {@link #isTransient(Throwable)}.
     */
    /**
     * Lo que queda cuando ya no hay nada que esperar: se registra y se deja pendiente.
     *
     * <p><b>No hay salida automática y tampoco humana para el buró</b>: nadie puede inventar un
     * puntaje. La solicitud se queda esperando a que alguien vuelva a pedir el reporte o la rechace
     * a mano, y por eso el log lleva el motivo — no para saber que falló, sino para saber
     * <em>qué</em> hacer con ella.
     *
     * <p>El mensaje se consume a propósito: devolverlo a la cola lo haría rebotar sobre un estado
     * que no cambia, bloqueando la partición para las solicitudes que sí se pueden resolver.
     */
    @Recover
    void leavePendingForManualIntervention(EvaluationNotReady e, ScoreRequestedPayload payload) {
        log.warn("Scoring PENDIENTE DE INTERVENCIÓN applicationId={} prospectId={} motivo={} — {}",
                payload.applicationId(), payload.prospectId(), e.reason(), e.getMessage());
    }
}

package com.fintech.scoring.application.service;

import com.fintech.scoring.application.port.out.BureauPrefetchRepository;
import com.fintech.scoring.application.port.out.BureauUnavailableException;
import com.fintech.scoring.application.port.out.CirculoGateway;
import com.fintech.scoring.application.port.out.CirculoReportRepository;
import com.fintech.scoring.application.port.out.dto.CirculoQueryRequest;
import com.fintech.scoring.domain.BureauPrefetch;
import com.fintech.scoring.domain.CirculoReport;
import com.fintech.scoring.domain.CirculoReportStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Consulta el buró al alta (prefetch) y deja el resultado guardado, sea cual sea.
 *
 * <h2>Qué se guarda cuando falla</h2>
 *
 * Todo: el reporte con su estado ({@code SUCCESS}, {@code NO_HIT}, {@code NOT_FOUND} o
 * {@code ERROR} con código y mensaje) y el prefetch con su estado, su motivo y cuántas veces se
 * llamó. Guardar el fallo es la constancia de que se consultó; lo que no puede pasar es
 * <b>evaluar</b> un reporte que no es {@code SUCCESS} —esa regla vive en
 * {@link ScoringEvaluationService}—.
 *
 * <h2>Reintento sólo de lo transitorio</h2>
 *
 * Un timeout, una conexión caída, un 5xx o un 429 ({@link BureauUnavailableException}) se reintentan
 * con backoff exponencial según {@link BureauRetryPolicy}. Un «no» del buró —un reporte
 * {@code ERROR} por un 4xx, {@code NO_HIT}, {@code NOT_FOUND}— no: repetir la misma petición da la
 * misma respuesta.
 *
 * <h2>Sin transacción abierta mientras se espera</h2>
 *
 * El método ya no es {@code @Transactional}: la consulta y los reintentos pueden tardar segundos, y
 * tener una conexión a la base tomada durante el backoff era quitársela a todos los demás. Cada
 * {@code save} es su propia transacción.
 *
 * <h2>Al terminar, despierta a quien esperaba</h2>
 *
 * Si una solicitud eligió producto antes de que el reporte llegara, su puntaje quedó en espera
 * ({@link PendingEvaluationService}). Al cerrar el prefetch —con el resultado que sea— se reanuda.
 */
@Service
public class BureauPrefetchService {

    private static final Logger log = LoggerFactory.getLogger(BureauPrefetchService.class);

    /** Cómo se espera entre reintentos; una prueba lo sustituye para no dormir de verdad. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    private final BureauPrefetchRepository  prefetchRepository;
    private final CirculoReportRepository   reportRepository;
    private final CirculoGateway            circuloGateway;
    private final BureauRetryPolicy         retryPolicy;
    private final Sleeper                   sleeper;

    /** Quien espera este reporte para puntuar; opcional para que las pruebas unitarias no lo exijan. */
    private PendingEvaluationService        pendingEvaluations;

    @Autowired(required = false)
    void setPendingEvaluations(PendingEvaluationService pendingEvaluations) {
        this.pendingEvaluations = pendingEvaluations;
    }

    @Autowired
    public BureauPrefetchService(BureauPrefetchRepository prefetchRepository,
                                 CirculoReportRepository reportRepository,
                                 CirculoGateway circuloGateway,
                                 BureauRetryPolicy retryPolicy) {
        this(prefetchRepository, reportRepository, circuloGateway, retryPolicy, d -> Thread.sleep(d.toMillis()));
    }

    public BureauPrefetchService(BureauPrefetchRepository prefetchRepository,
                                 CirculoReportRepository reportRepository,
                                 CirculoGateway circuloGateway,
                                 BureauRetryPolicy retryPolicy,
                                 Sleeper sleeper) {
        this.prefetchRepository = prefetchRepository;
        this.reportRepository   = reportRepository;
        this.circuloGateway     = circuloGateway;
        this.retryPolicy        = retryPolicy;
        this.sleeper            = sleeper;
    }

    public void initiate(UUID prospectId, String curp, String consentRef,
                         String firstName, String lastName1, String lastName2, String rfc,
                         LocalDate dateOfBirth,
                         String street, String exteriorNumber, String interiorNumber,
                         String neighborhood, String municipality, String city,
                         String state, String postalCode,
                         String prospectType, String productTypeIntent) {

        if (prefetchRepository.existsActiveByProspectId(prospectId)) {
            log.info("Active prefetch already exists for prospectId={} — skipping", prospectId);
            return;
        }

        UUID prefetchId = UUID.randomUUID();
        BureauPrefetch prefetch = BureauPrefetch.create(prefetchId, prospectId, curp, consentRef,
                prospectType, productTypeIntent);
        prefetch.markInProgress();
        prefetchRepository.save(prefetch);

        log.info("Initiating Círculo de Crédito prefetch prefetchId={} prospectId={} curp={}",
                prefetchId, prospectId, curp);

        CirculoQueryRequest request = new CirculoQueryRequest(
                lastName1, lastName2, firstName, curp, rfc, dateOfBirth,
                street, exteriorNumber, interiorNumber,
                neighborhood, municipality, city, state, postalCode);

        UUID reportId = UUID.randomUUID();
        try {
            CirculoReport report = queryWithRetry(prefetch, reportId, request);
            reportRepository.save(report);
            if (report.getStatus() == CirculoReportStatus.SUCCESS) {
                prefetch.markCompleted();
                // ADR-001: prefetch-only. The scoring evaluation is deferred to ScoreRequested
                // (when a product is selected) — NOT triggered here at onboarding.
            } else {
                log.warn("Círculo prefetch sin reporte utilizable prefetchId={} prospectId={} status={} code={} message={}",
                        prefetchId, prospectId, report.getStatus(), report.getErrorCode(), report.getErrorMessage());
                prefetch.markFailed(report.getStatus() + ": " + report.getErrorMessage());
            }
        } catch (BureauUnavailableException ex) {
            // Agotados los reintentos: queda constancia de la consulta fallida y de cuántas veces se hizo.
            log.error("Círculo de Crédito no disponible tras {} intentos prefetchId={} prospectId={}: {}",
                    prefetch.getAttempts(), prefetchId, prospectId, ex.getMessage());
            reportRepository.save(CirculoReport.builder(reportId, prefetchId, prospectId)
                    .status(CirculoReportStatus.ERROR)
                    .errorCode("CDC_UNAVAILABLE")
                    .errorMessage(ex.getMessage())
                    .build());
            prefetch.markFailed("BUREAU_UNAVAILABLE tras " + prefetch.getAttempts() + " intentos: " + ex.getMessage());
        } catch (Exception ex) {
            log.error("Círculo de Crédito prefetch failed prefetchId={} prospectId={}",
                    prefetchId, prospectId, ex);
            prefetch.markFailed(ex.getMessage());
        }

        prefetchRepository.save(prefetch);
        log.info("Círculo prefetch done prefetchId={} status={} attempts={}",
                prefetchId, prefetch.getStatus(), prefetch.getAttempts());

        // Si alguna solicitud eligió producto antes de que el reporte llegara, la despierta ahora:
        // con reporte la evalúa, y con el prefetch fallido deja dicho por qué no puede.
        if (pendingEvaluations != null) {
            try {
                pendingEvaluations.resumeFor(prospectId);
            } catch (RuntimeException ex) {
                log.error("No se pudieron reanudar los puntajes en espera de prospectId={}", prospectId, ex);
            }
        }
    }

    /** La consulta, reintentando lo transitorio con backoff exponencial. */
    private CirculoReport queryWithRetry(BureauPrefetch prefetch, UUID reportId, CirculoQueryRequest request) {
        int retry = 0;
        while (true) {
            prefetch.recordAttempt();
            try {
                return circuloGateway.query(reportId, prefetch.getPrefetchId(), prefetch.getProspectId(), request);
            } catch (BureauUnavailableException ex) {
                if (retry >= retryPolicy.maxRetries()) {
                    throw ex;
                }
                retry++;
                Duration wait = retryPolicy.backoffBefore(retry);
                log.warn("Círculo de Crédito falla transitoria (intento {}): {} — reintento {}/{} en {} ms prefetchId={}",
                        prefetch.getAttempts(), ex.getMessage(), retry, retryPolicy.maxRetries(), wait.toMillis(),
                        prefetch.getPrefetchId());
                try {
                    sleeper.sleep(wait);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw ex;
                }
            }
        }
    }
}

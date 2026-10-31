package com.fintech.scoring.application.service;

import com.fintech.scoring.application.BureauReportPending;
import com.fintech.scoring.application.EvaluationNotReady;
import com.fintech.scoring.application.port.out.CirculoReportRepository;
import com.fintech.scoring.application.port.out.PendingEvaluationRepository;
import com.fintech.scoring.application.port.out.PendingEvaluationRepository.PendingEvaluation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * El puntaje que llegó antes que el reporte de buró espera aquí, y se reanuda cuando el reporte
 * aterriza.
 *
 * <p>El listener de {@code score-requested} reintenta unos segundos —eso cubre la carrera corta, la
 * de tópicos que llegan desordenados—. Lo que no cubre es un buró lento: con timeout y reintentos
 * por backoff, un prefetch real puede tardar casi un minuto. Antes, agotado el reintento, la
 * solicitud se quedaba en PENDING_SCORING sin que nada la despertara. Ahora se estaciona, y el
 * prefetch, al terminar, la reanuda.
 *
 * <h2>Sin aviso perdido y sin evaluación doble</h2>
 *
 * Estacionar y terminar el prefetch pueden cruzarse: el reporte pudo guardarse justo entre el
 * último reintento y el {@code INSERT}. Por eso, tras estacionar, se vuelve a mirar si ya hay
 * reporte. Los dos lados miran después de escribir, así que al menos uno ve al otro; y como
 * reanudar es reclamar ({@link PendingEvaluationRepository#claimWaitingFor}), si los dos lo ven
 * sólo uno evalúa.
 */
@Service
public class PendingEvaluationService {

    private static final Logger log = LoggerFactory.getLogger(PendingEvaluationService.class);

    private final PendingEvaluationRepository repository;
    private final CirculoReportRepository     reportRepository;
    private final ScoringEvaluationService    evaluationService;

    public PendingEvaluationService(PendingEvaluationRepository repository,
                                    CirculoReportRepository reportRepository,
                                    ScoringEvaluationService evaluationService) {
        this.repository        = repository;
        this.reportRepository  = reportRepository;
        this.evaluationService = evaluationService;
    }

    /** Estaciona una solicitud cuyo reporte de buró todavía no existe. */
    public void park(UUID applicationId, UUID prospectId, String prospectType, String productType,
                     EvaluationNotReady.Reason reason) {
        repository.park(new PendingEvaluation(applicationId, prospectId, prospectType, productType, reason.name()));
        log.warn("Puntaje EN ESPERA del buró applicationId={} prospectId={} motivo={} — se reanuda cuando termine el prefetch",
                applicationId, prospectId, reason);
        if (reportRepository.findByProspectId(prospectId).isPresent()) {
            log.info("El reporte de prospectId={} aterrizó mientras se estacionaba: se reanuda ya", prospectId);
            resumeFor(prospectId);
        }
    }

    /**
     * Reanuda lo que esperaba a este prospecto. Lo llama el prefetch al terminar, sea cual sea el
     * resultado: con reporte se evalúa; con el prefetch fallido la evaluación lo dice y queda para
     * intervención, que es mejor que seguir esperando algo que no va a llegar.
     */
    public void resumeFor(UUID prospectId) {
        for (PendingEvaluation p : repository.claimWaitingFor(prospectId)) {
            try {
                evaluationService.evaluateForApplication(p.applicationId(), p.prospectId(),
                        p.prospectType(), p.productType());
                log.info("Puntaje reanudado applicationId={} prospectId={} (esperaba por {})",
                        p.applicationId(), p.prospectId(), p.reason());
            } catch (BureauReportPending e) {
                // No debería pasar —se reanuda porque el reporte ya está—, pero si pasa no se pierde:
                // vuelve a esperar, sin volver a mirar, para no girar en círculo.
                repository.park(new PendingEvaluation(p.applicationId(), p.prospectId(), p.prospectType(),
                        p.productType(), e.reason().name()));
                log.warn("Puntaje reanudado sin reporte applicationId={}: vuelve a esperar ({})",
                        p.applicationId(), e.getMessage());
            } catch (EvaluationNotReady e) {
                log.warn("Scoring PENDIENTE DE INTERVENCIÓN applicationId={} prospectId={} motivo={} — {}",
                        p.applicationId(), p.prospectId(), e.reason(), e.getMessage());
            } catch (RuntimeException e) {
                // Ya se reclamó: si no se devuelve a la tabla, la solicitud desaparece de la vista.
                // Queda esperando con el motivo, a la vista de quien intervenga.
                repository.park(new PendingEvaluation(p.applicationId(), p.prospectId(), p.prospectType(),
                        p.productType(), "RESUME_FAILED"));
                log.error("Falló el puntaje reanudado applicationId={} prospectId={} — queda en espera",
                        p.applicationId(), p.prospectId(), e);
            }
        }
    }
}

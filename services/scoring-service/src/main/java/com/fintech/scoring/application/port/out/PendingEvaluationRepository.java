package com.fintech.scoring.application.port.out;

import java.util.List;
import java.util.UUID;

/**
 * Puntajes que esperan al reporte de buró.
 *
 * <p>Dos procesos se cruzan aquí —el listener que se cansó de esperar y el prefetch que terminó— y
 * cualquiera de los dos puede llegar primero. Por eso reanudar es <b>reclamar</b>: sólo quien
 * reclama una solicitud la evalúa, y nunca la evalúan dos.
 */
public interface PendingEvaluationRepository {

    /** Estaciona la solicitud; si ya estaba (otra espera anterior), vuelve a quedar esperando. */
    void park(PendingEvaluation pending);

    /**
     * Reclama, de forma atómica, las solicitudes que esperan a este prospecto y las marca como
     * reanudadas. Dos llamadas simultáneas nunca devuelven la misma solicitud.
     */
    List<PendingEvaluation> claimWaitingFor(UUID prospectId);

    record PendingEvaluation(UUID applicationId, UUID prospectId, String prospectType,
                             String productType, String reason) {}
}

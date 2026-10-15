package com.fintech.compliance.application.service;

import com.fintech.compliance.application.port.out.ScreeningCheckRepository;
import com.fintech.compliance.domain.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Deja constancia de <b>cada</b> consulta a las listas.
 *
 * <h2>Por qué es un colaborador y no está dentro del cruce</h2>
 *
 * {@link ScreeningService#screen} es una <b>consulta pura</b>: entra una persona, sale un veredicto,
 * no toca nada. Eso es lo que permite probar la lógica de coincidencias con un repositorio en memoria
 * y sin base de datos, y convertirla en escritora obligaría a cada una de esas pruebas a cargar con
 * una tabla que no le importa.
 *
 * <p>La garantía que sí hace falta es otra: <b>que todo veredicto registrado tenga su consulta
 * registrada</b>. Y eso se sostiene donde se registran los veredictos —
 * {@link PersonVerificationService} y {@link RescreenService}, los dos únicos sitios — con una prueba
 * que afirma el emparejamiento.
 *
 * <h2>Y por qué se escribe también cuando nada cambió</h2>
 *
 * Porque lo que la norma pide demostrar es <b>que se miró</b>, no que se encontró algo. Un re-cruce
 * que confirma que la persona sigue limpia es diligencia ejecutada, y si no deja rastro es
 * indistinguible de no haberlo corrido. Guardar sólo los cambios haría que el expediente de la
 * persona más limpia de la cartera estuviera vacío.
 */
@Service
public class ScreeningJournal {

    private static final Logger log = LoggerFactory.getLogger(ScreeningJournal.class);

    private final ScreeningCheckRepository checks;

    public ScreeningJournal(ScreeningCheckRepository checks) {
        this.checks = checks;
    }

    /**
     * Guarda la consulta tal como ocurrió.
     *
     * <p><b>No propaga su propio fallo.</b> Si la bitácora no se puede escribir, el veredicto —que
     * es lo que detiene el dinero— ya está decidido y no se tira por eso: quedarse sin constancia es
     * malo, dejar pasar a alguien por no haber podido escribirla sería peor. El fallo se registra en
     * ERROR para que se vea.
     */
    public void record(UUID prospectId, ScreeningSubject subject,
                       ScreeningTrigger trigger, ScreeningOutcome outcome) {
        if (prospectId == null || outcome == null) return;
        try {
            ScreeningCheck check = checks.save(
                    ScreeningCheck.of(prospectId, subject, trigger, outcome));

            if (!check.hadListsToCheckAgainst()) {
                // Es una consulta que no comprobó nada, y el expediente tiene que decirlo así. Sin
                // esta distinción, «PENDING» con cero candidatos se parece demasiado a «limpio».
                log.error("[BITÁCORA] Consulta de prospectId={} registrada SIN listas vigentes: no "
                          + "comprobó nada y por eso el veredicto es {}", prospectId, outcome.status());
            } else {
                log.info("[BITÁCORA] {} de prospectId={} → {} · {}",
                        trigger, prospectId, outcome.status(), outcome.summary());
            }
        } catch (RuntimeException e) {
            log.error("[BITÁCORA] NO se pudo registrar la consulta de prospectId={} ({} · {}): {}. "
                      + "El veredicto sí quedó guardado; lo que falta es su constancia.",
                    prospectId, trigger, outcome.status(), e.toString(), e);
        }
    }

    /** El historial de una persona, lo más reciente primero. */
    public List<ScreeningCheck> historyOf(UUID prospectId, int limit) {
        return checks.findByProspectId(prospectId, limit);
    }

    public long countFor(UUID prospectId) {
        return checks.countByProspectId(prospectId);
    }
}

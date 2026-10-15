package com.fintech.compliance.application.service;

import com.fintech.compliance.application.port.out.PersonVerificationRepository;
import com.fintech.compliance.application.port.out.RescreenRunRepository;
import com.fintech.compliance.application.port.out.VerificationEventPublisher;
import com.fintech.compliance.domain.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Vuelve a cruzar a la población viva contra las listas vigentes.
 *
 * <h2>Por qué hace falta: sin esto, «AML en cada disposición» es falso</h2>
 *
 * El veredicto se calcula al alta y, sin re-cruce, <b>no se vuelve a tocar nunca</b>. La compuerta de
 * la disposición leería para siempre un dictamen emitido contra listas de hace años. El AML no
 * depende de la persona sino de la lista, y la lista cambia: sin volver a cruzar, el control caduca
 * el día después de darlo de alta.
 *
 * <h2>Los veredictos vigentes NO se invalidan mientras corre</h2>
 *
 * <b>Decisión del negocio (2026-09-14).</b> Al activarse una lista nueva se podría marcar todo como
 * caduco de inmediato: ventana cero, y <b>todos</b> los desembolsos detenidos hasta terminar. Se
 * eligió lo contrario — los veredictos siguen valiendo mientras corre el re-cruce— y se asume la
 * ventana, mitigada corriendo en ventana de mantenimiento.
 *
 * <p>Que no se invalide nada aquí <b>es la decisión</b>, no un olvido. Lo que la hace defendible son
 * dos cosas que sí están: esta corrida queda registrada con su duración, y cada disposición guarda
 * una foto del veredicto que la gobernó. Con las dos, una revisión puede distinguir «salió dentro de
 * la ventana conocida» de «se dejó pasar».
 *
 * <h2>Sólo se republica a quien le CAMBIÓ el veredicto</h2>
 *
 * Republicar a los N sería inundar el tópico y hacer que `party` y `credit-portfolio` reescriban
 * miles de filas idénticas en cada corrida. Y perdería la señal: un evento de verificación debería
 * significar «algo cambió para esta persona».
 */
@Service
public class RescreenService {

    private static final Logger log = LoggerFactory.getLogger(RescreenService.class);

    /** Páginas, no todo de golpe: una corrida interrumpida conserva lo que alcanzó a actualizar. */
    private static final int PAGE = 200;

    public static final String TRIGGER_LIST_ACTIVATED = "LIST_ACTIVATED";
    public static final String TRIGGER_SCHEDULED      = "SCHEDULED";
    public static final String TRIGGER_MANUAL         = "MANUAL";

    private final PersonVerificationRepository verifications;
    private final RescreenRunRepository runs;
    private final ScreeningService screening;
    private final VerificationEventPublisher publisher;
    private final ScreeningJournal journal;

    public RescreenService(PersonVerificationRepository verifications,
                           RescreenRunRepository runs,
                           ScreeningService screening,
                           VerificationEventPublisher publisher,
                           ScreeningJournal journal) {
        this.verifications = verifications;
        this.runs          = runs;
        this.screening     = screening;
        this.publisher     = publisher;
        this.journal       = journal;
    }

    public RescreenRun rescreenAll(UUID triggeredByVersionId, String trigger) {
        RescreenRun run = runs.save(RescreenRun.start(triggeredByVersionId, trigger));
        log.info("[RE-CRUCE] arranca runId={} disparo={} lista={}", run.getRunId(), trigger,
                triggeredByVersionId);

        try {
            int page = 0;
            List<PersonVerification> lote;
            while (!(lote = verifications.findPage(page, PAGE)).isEmpty()) {
                for (PersonVerification v : lote) {
                    run.countScreened(rescreenOne(v));
                }
                runs.save(run);   // el avance se guarda por lote, no sólo al final
                page++;
            }
            run.finish();
            runs.save(run);

            log.info("[RE-CRUCE] runId={} terminó en {} — {} revisados, {} con veredicto nuevo",
                    run.getRunId(), run.duration(), run.getTotalScreened(), run.getChangedCount());
            return run;

        } catch (RuntimeException e) {
            // Lo actualizado antes del fallo queda: es mejor que lo que había. Lo que no se puede
            // es dar la corrida por buena — no se sabe a quién no se llegó.
            run.fail(e.toString());
            runs.save(run);
            log.error("[RE-CRUCE] runId={} FALLÓ tras {} revisados: {}",
                    run.getRunId(), run.getTotalScreened(), e.toString());
            throw e;
        }
    }

    /**
     * Re-cruza a una persona. Devuelve si le cambió el veredicto.
     *
     * <p><b>Una adjudicación humana no se pisa en silencio.</b> Si cumplimiento despejó a alguien
     * —«se llama igual pero nació en 1985 y el de la lista en 1962»— y el re-cruce vuelve a
     * encontrar candidatos, la persona vuelve a la cola <b>conservando</b> quién la despejó y con
     * qué argumento. El analista ve que ya había sido revisada y contra qué, en vez de partir de
     * cero; y nadie queda despejado para siempre por una decisión tomada contra otra lista.
     */
    /*
     * Sin `@Transactional`, y a propósito.
     *
     * Este método se llama desde `rescreenAll` sobre `this`, así que el proxy de Spring no
     * interviene: la anotación no haría nada y sólo diría que hay una transacción donde no la hay.
     * Es el mismo tropiezo que la auto-invocación de `@Retryable`, y ahí costó descubrirlo.
     *
     * Y tampoco hace falta: cada persona se guarda por su cuenta —`save` ya es transaccional— y eso
     * es justo lo que se quiere en un lote largo. Envolver las 50 000 en una sola transacción haría
     * que un fallo al final deshiciera todo el trabajo, cuando lo actualizado es mejor que lo que
     * había.
     */
    boolean rescreenOne(PersonVerification v) {
        ScreeningStatus antes = v.getScreeningStatus();

        // Sin nombre guardado no hay contra qué cruzar. Se salta y NO se toca su veredicto:
        // marcarlo CLEARED sería declarar limpio a quien no se pudo mirar, que es exactamente el
        // fallo silencioso que todo este diseño evita.
        if (!v.isScreenable()) {
            log.warn("[RE-CRUCE] partyId={} sin datos para cruzar — se conserva su veredicto {}",
                    v.getPartyId(), antes);
            return false;
        }

        ScreeningSubject subject = v.asSubject();
        ScreeningOutcome outcome  = screening.screen(subject);

        v.recordScreening(outcome);
        verifications.save(v);

        // Se registra la consulta SIEMPRE, también cuando el veredicto no cambió.
        //
        // Es lo contrario de lo que sugiere el instinto —«guarda sólo lo que cambió»— y es el punto:
        // lo que la norma pide demostrar es QUE SE MIRÓ. Un re-cruce que confirma que alguien sigue
        // limpio es diligencia ejecutada, y guardar sólo los cambios dejaría el expediente de la
        // persona más limpia de la cartera completamente vacío.
        journal.record(v.getProspectId(), subject, ScreeningTrigger.RESCREEN, outcome);

        boolean cambio = antes != v.getScreeningStatus();
        if (cambio) {
            publisher.publishVerificationCompleted(v);
            log.warn("[RE-CRUCE] partyId={} pasó de {} a {} — {}",
                    v.getPartyId(), antes, v.getScreeningStatus(), outcome.summary());
        }
        return cambio;
    }
}

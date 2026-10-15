package com.fintech.compliance.application.service;

import com.fintech.compliance.application.port.out.PersonVerificationRepository;
import com.fintech.compliance.application.port.out.VerificationEventPublisher;
import com.fintech.compliance.domain.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Evalúa a una persona al darse de alta: identidad y listas, una sola vez, sin importar su rol.
 *
 * <h2>Se dispara con el alta, no con el producto</h2>
 *
 * El detonante es {@code origination.prospect-created}, que es el hecho «se registró una persona» y
 * por el que ya pasan los tres caminos —app móvil, beneficiaria de distribuidor y distribuidor—.
 * El rol no cambia <b>si</b> se evalúa; sólo cambia cuándo ocurre el alta.
 *
 * <h2>Lo que hace hoy y lo que falta</h2>
 *
 * El <b>screening</b> corre completo: cruza contra las listas vigentes y emite veredicto.
 *
 * La <b>identidad</b> queda en revisión, que es el estado correcto mientras no haya contrato con
 * proveedor: hoy todo se resuelve a mano, y es lo que los nueve productos del catálogo declaran
 * ({@code automationEnabled = false}). Cuando exista el adaptador de Incode/Jumio, aquí se intenta
 * primero con él y sólo cae a revisión lo que no resuelva — sin tocar a quien consulta el veredicto.
 */
@Service
public class PersonVerificationService {

    private static final Logger log = LoggerFactory.getLogger(PersonVerificationService.class);

    private final PersonVerificationRepository repository;
    private final ScreeningService screening;
    private final VerificationEventPublisher publisher;
    private final ScreeningJournal journal;

    public PersonVerificationService(PersonVerificationRepository repository,
                                     ScreeningService screening,
                                     VerificationEventPublisher publisher,
                                     ScreeningJournal journal) {
        this.repository = repository;
        this.screening  = screening;
        this.publisher  = publisher;
        this.journal    = journal;
    }

    /**
     * Evalúa a una persona recién registrada.
     *
     * <p><b>Idempotente por prospecto.</b> Kafka reentrega, y una segunda evaluación no sólo sería
     * trabajo repetido: pisaría una adjudicación ya hecha por una persona y devolvería a la cola a
     * alguien que cumplimiento ya había despejado.
     */
    @Transactional
    public PersonVerification evaluateNewPerson(ScreeningSubject subject, UUID prospectId) {
        return evaluate(subject, prospectId, ScreeningTrigger.ONBOARDING);
    }

    /**
     * Evalúa a una persona que <b>ya existía</b>: remediación de cartera histórica, revisión de un
     * caso.
     *
     * <p>Es el mismo trabajo con otro motivo, y el motivo se guarda porque <b>demuestra algo
     * distinto</b>: una consulta de alta es diligencia al dar entrada a alguien; una bajo demanda es
     * que alguien preguntó — y quién preguntó y cuándo es justo lo que una revisión quiere ver.
     */
    @Transactional
    public PersonVerification evaluateOnDemand(ScreeningSubject subject, UUID prospectId) {
        return evaluate(subject, prospectId, ScreeningTrigger.ON_DEMAND);
    }

    private PersonVerification evaluate(ScreeningSubject subject, UUID prospectId,
                                        ScreeningTrigger trigger) {
        Optional<PersonVerification> ya = repository.findByProspectId(prospectId);
        if (ya.isPresent()) {
            log.info("El prospecto {} ya tenía verificación ({}) — no se reevalúa",
                    prospectId, ya.get().getVerificationId());
            return ya.get();
        }

        PersonVerification verification = PersonVerification.start(prospectId, subject.partyId(), subject);

        ScreeningOutcome outcome = screening.screen(subject);
        verification.recordScreening(outcome);

        // Constancia de la consulta, no sólo del veredicto. Es lo que permite decir después «se la
        // consultó el día 3 contra la lista del día 1 y salió limpia» en vez de sólo «hoy está
        // señalada» — que no distingue «no lo sabíamos» de «lo dejamos pasar».
        journal.record(prospectId, subject, trigger, outcome);

        // Sin proveedor, la identidad va a la cola. No es un fallo: es el modo vigente.
        verification.recordIdentity(IdentityStatus.IN_REVIEW);

        PersonVerification saved = repository.save(verification);
        publisher.publishVerificationCompleted(saved);

        log.info("Verificación de prospectId={} → identidad={} listas={} · {}",
                prospectId, saved.getIdentityStatus(), saved.getScreeningStatus(), outcome.summary());
        return saved;
    }

    /**
     * Cumplimiento despeja o confirma la coincidencia de listas.
     *
     * <p>Es lo que hace operable el control: sin esto, cualquier homónimo —que es el caso común—
     * queda bloqueado para siempre y el screening pasa de ser un control a ser un muro.
     */
    @Transactional
    public PersonVerification adjudicateScreening(UUID verificationId, ScreeningStatus decision,
                                                  String by, String notes) {
        PersonVerification v = repository.findById(verificationId)
                .orElseThrow(() -> new VerificationNotFoundException(verificationId));

        v.adjudicate(decision, by, notes);
        PersonVerification saved = repository.save(v);

        publisher.publishVerificationCompleted(saved);

        if (decision == ScreeningStatus.CONFIRMED_MATCH) {
            // No termina en «rechazar al cliente»: obliga a reportar. `audit` lo consume y escribe
            // el UIFReport, que es de quien es la tabla.
            publisher.publishConfirmedMatch(saved, null, null);
            log.warn("COINCIDENCIA CONFIRMADA partyId={} por {} — {}", saved.getPartyId(), by, notes);
        } else {
            log.info("Listas despejadas partyId={} por {} — {}", saved.getPartyId(), by, notes);
        }
        return saved;
    }

    /**
     * Un analista dictamina la identidad.
     *
     * <h2>Es la otra mitad de lo que hace operable el control</h2>
     *
     * {@link #adjudicateScreening} saca a los homónimos de la cola de listas. Esto saca a la gente
     * de la cola de <b>identidad</b> — y mientras no haya proveedor de KYC, por esa cola pasa el
     * 100 % del volumen. Sin esto, {@code IN_REVIEW} no tiene salida: la persona queda bloqueada en
     * todas las compuertas para siempre y nadie puede desbloquearla.
     *
     * <p>Republica el veredicto al terminar, que es lo que hace que {@code party} y las proyecciones
     * de las compuertas se enteren. Sin esa republicación el dictamen quedaría guardado aquí y la
     * persona seguiría bloqueada río abajo, que es el peor de los dos mundos: firmado y sin efecto.
     */
    @Transactional
    public PersonVerification adjudicateIdentity(UUID verificationId, IdentityStatus decision,
                                                 String by, String notes) {
        PersonVerification v = repository.findById(verificationId)
                .orElseThrow(() -> new VerificationNotFoundException(verificationId));
        v.adjudicateIdentity(decision, by, notes);
        PersonVerification saved = repository.save(v);
        publisher.publishVerificationCompleted(saved);

        log.info("Identidad dictaminada partyId={} prospectId={} → {} por {} — {}",
                saved.getPartyId(), saved.getProspectId(), decision, by, notes);
        return saved;
    }

    @Transactional(readOnly = true)
    public Optional<PersonVerification> byProspect(UUID prospectId) {
        return repository.findByProspectId(prospectId);
    }

    @Transactional(readOnly = true)
    public Optional<PersonVerification> byParty(UUID partyId) {
        return repository.findByPartyId(partyId);
    }
}

package com.fintech.collections.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Un contacto con el deudor, lo haya hecho una persona o el sistema.
 *
 * <p>CT-01/CT-02/CT-03: la ventana horaria y el tope diario los impone el servicio, no la entidad.
 *
 * <p>La tabla guardaba sólo lo que un agente capturaba a mano, mientras notifications llevaba su
 * propio historial de lo enviado. Eran dos bitácoras que nadie cruzaba, y la del caso —la que se
 * audita— no tenía los mensajes. Ahora todo contacto acaba aquí; {@link #origin} dice quién lo
 * originó y {@link #notificationId} enlaza con el contenido exacto que salió.
 */
@Entity
@Table(name = "contact_attempts", schema = "collections")
public class ContactAttempt {

    @Id
    @Column(name = "attempt_id", nullable = false, updatable = false)
    private UUID attemptId;

    @Column(name = "case_id", nullable = false, updatable = false)
    private UUID caseId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private ContactChannel channel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private ContactResult result;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private ContactOrigin origin;

    /** Nulo en los automáticos: no hay persona a quien atribuirle el acto. */
    @Column(name = "agent_id", updatable = false)
    private String agentId;

    /** El mensaje concreto en notifications-service. Nulo en los manuales. */
    @Column(name = "notification_id", updatable = false)
    private UUID notificationId;

    /** El escalón de tono con que salió, cuando viene de la cadencia. */
    @Column(name = "dunning_step", updatable = false)
    private Integer dunningStep;

    @Column(name = "attempted_at", nullable = false, updatable = false)
    private Instant attemptedAt;

    protected ContactAttempt() {}

    /** Contacto capturado por un agente. Cuenta contra el tope diario. */
    public static ContactAttempt record(UUID caseId, ContactChannel channel, ContactResult result, String agentId) {
        ContactAttempt a = base(caseId, channel, result);
        a.origin  = ContactOrigin.MANUAL;
        a.agentId = agentId;
        return a;
    }

    /**
     * Mensaje enviado por la cadencia, registrado al confirmarse su entrega.
     *
     * <p>No cuenta contra el tope diario y no lleva agente. Se escribe cuando notifications avisa el
     * desenlace, no cuando se pide el envío: registrar la intención haría que la bitácora afirmara
     * que se contactó a alguien a quien quizá no se le pudo entregar nada.
     */
    public static ContactAttempt automatic(UUID caseId, ContactChannel channel, ContactResult result,
                                            UUID notificationId, Integer dunningStep, Instant occurredAt) {
        ContactAttempt a = base(caseId, channel, result);
        a.origin         = ContactOrigin.AUTOMATIC;
        a.notificationId = notificationId;
        a.dunningStep    = dunningStep;
        if (occurredAt != null) a.attemptedAt = occurredAt;
        return a;
    }

    private static ContactAttempt base(UUID caseId, ContactChannel channel, ContactResult result) {
        ContactAttempt a = new ContactAttempt();
        a.attemptId   = UUID.randomUUID();
        a.caseId      = caseId;
        a.channel     = channel;
        a.result      = result;
        a.attemptedAt = Instant.now();
        return a;
    }

    public UUID getAttemptId()          { return attemptId; }
    public UUID getCaseId()             { return caseId; }
    public ContactChannel getChannel()  { return channel; }
    public ContactResult getResult()    { return result; }
    public ContactOrigin getOrigin()    { return origin; }
    public String getAgentId()          { return agentId; }
    public UUID getNotificationId()     { return notificationId; }
    public Integer getDunningStep()     { return dunningStep; }
    public Instant getAttemptedAt()     { return attemptedAt; }
}

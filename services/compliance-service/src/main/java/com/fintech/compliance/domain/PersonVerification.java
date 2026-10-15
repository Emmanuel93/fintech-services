package com.fintech.compliance.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * El veredicto de una persona: identidad y listas, juntos pero <b>separados</b>.
 *
 * <h2>Uno por persona, no uno por crédito</h2>
 *
 * Una persona se verifica una vez. Antes el veredicto vivía dentro de la colocación
 * ({@code Placement.identityDecision}), lo que significaba que la misma persona podía tener dos
 * juicios distintos sobre su identidad según por dónde hubiera entrado — y que el titular de uso
 * propio no tenía ninguno, porque no hay colocación.
 *
 * <h2>Dos campos y no una bandera «verificado»</h2>
 *
 * Porque no se resuelven igual. Si el KYC falla, lo arregla <b>la persona</b> con mejor evidencia:
 * repetir la selfie con más luz. Si el screening sale con candidato, la persona <b>no puede hacer
 * nada</b> —no va a cambiarse el nombre, y la lista no cambia porque insista—: lo resuelve
 * cumplimiento adjudicando.
 *
 * <p>Mandarle «no pudimos verificar tu identidad, vuelve a intentarlo» a alguien que coincidió con
 * una lista es absurdo. Ésa es la razón práctica de que sean dos campos.
 *
 * <h2>Y por qué guarda contra qué versiones de lista se corrió</h2>
 *
 * Las listas cambian. Alguien despejado hace seis meses puede estar hoy. Un {@code CLEARED} sin
 * versión no se puede auditar ni re-correr cuando se publique una lista nueva.
 */
@Entity
@Table(name = "person_verifications", schema = "compliance")
public class PersonVerification {

    @Id
    @Column(name = "verification_id", nullable = false, updatable = false)
    private UUID verificationId;

    /** Puede ser nulo un instante: el Party lo crea `party` al consumir el mismo evento. */
    @Column(name = "party_id")
    private UUID partyId;

    @Column(name = "prospect_id", nullable = false, updatable = false, unique = true)
    private UUID prospectId;

    @Enumerated(EnumType.STRING)
    @Column(name = "identity_status", length = 20, nullable = false)
    private IdentityStatus identityStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "screening_status", length = 20, nullable = false)
    private ScreeningStatus screeningStatus;

    /** Contra qué versiones de lista se corrió, separadas por coma. */
    @Column(name = "screened_list_versions", length = 1000)
    private String screenedListVersions;

    @Column(name = "screened_at")       private Instant screenedAt;
    @Column(name = "decided_by",  length = 120) private String decidedBy;
    @Column(name = "decided_at")        private Instant decidedAt;
    @Column(name = "decision_notes", length = 1000) private String decisionNotes;

    // ── Con qué datos se cruzó ───────────────────────────────────────────────────────────────
    //
    // Sin ellos el re-cruce no puede correr: el veredicto no dice a quién se buscó. Y son también
    // lo que hace auditable la decisión — «se buscó ESTE nombre contra ESA lista».

    @Column(name = "subject_full_name", length = 400)     private String subjectFullName;
    @Column(name = "subject_date_of_birth")               private java.time.LocalDate subjectDateOfBirth;
    @Column(name = "subject_curp", length = 18)           private String subjectCurp;
    @Column(name = "subject_rfc", length = 13)            private String subjectRfc;
    @Column(name = "subject_country", length = 2)         private String subjectCountry;

    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected PersonVerification() { }

    /**
     * Arranca en {@code PENDING} las dos cosas, y <b>pendiente bloquea</b>.
     *
     * <p>No haber comprobado no es haber salido limpio. Si el estado inicial dejara pasar, la
     * ventana entre el alta y el veredicto sería exactamente el hueco por el que alguien opera sin
     * verificar — y nadie lo notaría, porque nada falla.
     */
    public static PersonVerification start(UUID prospectId, UUID partyId) {
        return start(prospectId, partyId, null);
    }

    public static PersonVerification start(UUID prospectId, UUID partyId, ScreeningSubject subject) {
        PersonVerification v = new PersonVerification();
        if (subject != null) {
            v.subjectFullName     = subject.fullName();
            v.subjectDateOfBirth  = subject.dateOfBirth();
            v.subjectCurp         = subject.curp();
            v.subjectRfc          = subject.rfc();
            v.subjectCountry      = subject.country();
        }
        v.verificationId  = UUID.randomUUID();
        v.prospectId      = prospectId;
        v.partyId         = partyId;
        v.identityStatus  = IdentityStatus.PENDING;
        v.screeningStatus = ScreeningStatus.PENDING;
        v.createdAt       = Instant.now();
        v.updatedAt       = v.createdAt;
        return v;
    }

    public void attachParty(UUID partyId) {
        if (this.partyId == null) { this.partyId = partyId; this.updatedAt = Instant.now(); }
    }

    /** Registra el resultado del cruce contra listas, con las versiones usadas. */
    public void recordScreening(ScreeningOutcome outcome) {
        this.screeningStatus       = outcome.status();
        this.screenedListVersions  = outcome.listVersions().stream()
                .map(UUID::toString).reduce((a, b) -> a + "," + b).orElse(null);
        this.screenedAt = Instant.now();
        this.updatedAt  = this.screenedAt;
    }

    /**
     * El veredicto de identidad que escribe el <b>sistema</b>: la evaluación automática o la falta
     * de proveedor que manda la identidad a la cola.
     *
     * <p>No pide autor porque no lo hay: lo firmó un proceso. El dictamen de una <b>persona</b> va
     * por {@link #adjudicateIdentity(IdentityStatus, String, String)}, que sí lo exige.
     */
    public void recordIdentity(IdentityStatus status) {
        this.identityStatus = status;
        this.updatedAt      = Instant.now();
    }

    /**
     * Un analista dictamina la identidad. <b>Con autor y con argumento.</b>
     *
     * <h2>Por qué esto tiene que existir y pedir lo mismo que el de listas</h2>
     *
     * Mientras no haya proveedor de KYC, <b>toda</b> identidad llega aquí: ése es el modo vigente,
     * no una excepción. Sin este método el {@code IN_REVIEW} no tenía salida y la cola era un muro —
     * y la plataforma entera quedaba bloqueada esperando algo que nadie podía firmar.
     *
     * <p>Y exige autor y nota por la misma razón que el veredicto de listas: una decisión de
     * identidad sin quién la firmó no sustenta nada ante una revisión. La asimetría anterior —listas
     * con autor obligatorio, identidad sin nada— no tenía fundamento: las dos son decisiones
     * regulatorias sobre una persona.
     */
    public void adjudicateIdentity(IdentityStatus decision, String by, String notes) {
        if (decision != IdentityStatus.VERIFIED && decision != IdentityStatus.REJECTED) {
            throw new IllegalArgumentException(
                    "Dictaminar una identidad es comprobarla o rechazarla; " + decision
                            + " no es una decisión");
        }
        if (by == null || by.isBlank()) {
            throw new IllegalArgumentException(
                    "Un veredicto de identidad sin autor no puede existir");
        }
        if (notes == null || notes.isBlank()) {
            throw new IllegalArgumentException(
                    "Falta el argumento: es lo que sustenta el dictamen ante una revisión");
        }
        this.identityStatus = decision;
        this.decidedBy      = by;
        this.decisionNotes  = notes.length() > 1000 ? notes.substring(0, 1000) : notes;
        this.decidedAt      = Instant.now();
        this.updatedAt      = this.decidedAt;
    }

    /**
     * Cumplimiento despeja o confirma un candidato.
     *
     * <p>Exige autor y nota: un veredicto de listas sin quién lo firmó y con qué argumento no
     * sustenta nada ante una revisión — y el argumento es justo lo valioso («nació en 1985, el de la
     * lista en 1962»).
     */
    public void adjudicate(ScreeningStatus decision, String by, String notes) {
        if (decision != ScreeningStatus.CLEARED && decision != ScreeningStatus.CONFIRMED_MATCH) {
            throw new IllegalArgumentException(
                    "Adjudicar es despejar o confirmar; " + decision + " no es una decisión");
        }
        if (by == null || by.isBlank()) {
            throw new IllegalArgumentException("Un veredicto de listas sin autor no puede existir");
        }
        if (notes == null || notes.isBlank()) {
            throw new IllegalArgumentException(
                    "Falta el argumento: es lo que sustenta la decisión ante una revisión");
        }
        this.screeningStatus = decision;
        this.decidedBy       = by;
        this.decisionNotes   = notes.length() > 1000 ? notes.substring(0, 1000) : notes;
        this.decidedAt       = Instant.now();
        this.updatedAt       = this.decidedAt;
    }

    /**
     * La compuerta. <b>Las dos cosas, y en positivo.</b>
     *
     * <p>Escrito como «no bloquea si…» se rompe en silencio el día que alguien añade un estado: el
     * nuevo cae fuera de la lista de bloqueos y pasa sin que nadie lo haya decidido.
     */
    public boolean allowsToProceed() {
        return identityStatus == IdentityStatus.VERIFIED && screeningStatus.allowsToProceed();
    }

    /** Por qué no pasa, para que quien lo consulte no tenga que deducirlo. */
    public String blockReason() {
        if (allowsToProceed()) return null;
        if (identityStatus != IdentityStatus.VERIFIED && !screeningStatus.allowsToProceed()) {
            return "identidad " + identityStatus + " y listas " + screeningStatus;
        }
        return identityStatus != IdentityStatus.VERIFIED
                ? "identidad " + identityStatus
                : "listas " + screeningStatus;
    }

    /** El sujeto con el que se cruzó, para poder volver a cruzarlo. */
    public ScreeningSubject asSubject() {
        return new ScreeningSubject(partyId, subjectFullName, subjectDateOfBirth,
                subjectCurp, subjectRfc, subjectCountry);
    }

    /** Si hay con qué volver a cruzar. Sin nombre no se puede, y declarar limpio a quien no se pudo mirar sería peor. */
    public boolean isScreenable() {
        return subjectFullName != null && !subjectFullName.isBlank();
    }

    public UUID getVerificationId()             { return verificationId; }
    public UUID getPartyId()                    { return partyId; }
    public UUID getProspectId()                 { return prospectId; }
    public IdentityStatus getIdentityStatus()   { return identityStatus; }
    public ScreeningStatus getScreeningStatus() { return screeningStatus; }
    public String getScreenedListVersions()     { return screenedListVersions; }
    public String getDecidedBy()                { return decidedBy; }
    public String getDecisionNotes()            { return decisionNotes; }
    public Instant getUpdatedAt()               { return updatedAt; }
}

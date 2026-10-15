package com.fintech.compliance.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

/**
 * Una consulta a las listas, tal como ocurrió. <b>No se modifica nunca.</b>
 *
 * <h2>Por qué hace falta si ya existe el veredicto</h2>
 *
 * {@link PersonVerification} guarda el veredicto <b>vigente</b> y se sobrescribe: cada re-cruce pisa
 * el anterior. Eso responde «¿puede operar hoy?» y <b>no</b> responde la pregunta que hace una
 * revisión:
 *
 * <blockquote>
 * «El 3 de septiembre le dieron un crédito a esta persona que hoy aparece en una lista de
 * sancionados. ¿La consultaron? ¿Cuándo? ¿Qué decía la lista <b>ese día</b>?»
 * </blockquote>
 *
 * Con sólo el veredicto vigente, la respuesta es «hoy está señalada» — que no distingue
 * <b>«no lo sabíamos»</b> de <b>«lo dejamos pasar»</b>. Y ésa es exactamente la distinción por la
 * que se responde ante el regulador.
 *
 * <h2>Qué se guarda y por qué cada campo</h2>
 *
 * <ul>
 *   <li><b>La fecha de la consulta</b> — la prueba de diligencia es cuándo se miró, no qué se
 *       concluyó.</li>
 *   <li><b>El resultado de ese momento</b> — «estaba limpia el día 3» es una afirmación distinta de
 *       «está señalada hoy», y las dos tienen que poder convivir en el expediente.</li>
 *   <li><b>Contra qué versiones de lista</b> — sin esto el resultado no es reproducible: decir
 *       «salió limpia» sin decir contra qué lista no sustenta nada, porque la lista de ese día ya no
 *       es la de hoy. Con la versión, cualquiera puede volver a correr la consulta y obtener lo
 *       mismo.</li>
 *   <li><b>Los datos con los que se consultó</b> — nombre, CURP, RFC, fecha. Si la persona corrigió
 *       su nombre después, el resultado de entonces sólo se explica con el nombre de entonces. Sin
 *       esto, una consulta vieja parecería mal hecha.</li>
 *   <li><b>Cuántos candidatos y el más fuerte</b> — para distinguir «no coincidió con nadie» de
 *       «coincidió con cuatro y se descartaron», que es trabajo hecho y hay que poder mostrarlo.</li>
 * </ul>
 *
 * <h2>Append-only, y cómo se sostiene</h2>
 *
 * No tiene un solo mutador: se construye con {@link #of} y no hay forma de cambiarla después. Un
 * registro de auditoría que se puede editar no es un registro de auditoría — y la garantía tiene que
 * estar en el tipo, no en la disciplina de quien lo use.
 */
@Entity
@Table(name = "screening_checks", schema = "compliance")
public class ScreeningCheck {

    @Id
    @Column(name = "check_id", nullable = false, updatable = false)
    private UUID checkId;

    /** La llave que comparten compliance, party y los flujos de alta. */
    @Column(name = "prospect_id", nullable = false, updatable = false)
    private UUID prospectId;

    /** Puede ser nulo: el primer cruce ocurre antes de que exista el Party. */
    @Column(name = "party_id", updatable = false)
    private UUID partyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_reason", length = 20, nullable = false, updatable = false)
    private ScreeningTrigger trigger;

    @Enumerated(EnumType.STRING)
    @Column(name = "result", length = 20, nullable = false, updatable = false)
    private ScreeningStatus result;

    @Column(name = "candidate_count", nullable = false, updatable = false)
    private int candidateCount;

    /** La evidencia más fuerte que se encontró, o nulo si no hubo ninguna. */
    @Column(name = "strongest_reason", length = 30, updatable = false)
    private String strongestReason;

    /** Con qué entrada de lista coincidió lo más fuerte. Para que el expediente nombre a alguien. */
    @Column(name = "strongest_listed_name", length = 400, updatable = false)
    private String strongestListedName;

    /** Contra qué versiones se consultó. Vacío significa <b>que no había listas</b>, y eso importa. */
    @Column(name = "list_versions", length = 1000, updatable = false)
    private String listVersions;

    @Column(name = "subject_full_name", length = 400, updatable = false)  private String subjectFullName;
    @Column(name = "subject_curp", length = 18, updatable = false)        private String subjectCurp;
    @Column(name = "subject_rfc", length = 13, updatable = false)         private String subjectRfc;
    @Column(name = "subject_date_of_birth", updatable = false)            private LocalDate subjectDateOfBirth;

    @Column(name = "checked_at", nullable = false, updatable = false)
    private Instant checkedAt;

    protected ScreeningCheck() { }

    public static ScreeningCheck of(UUID prospectId, ScreeningSubject subject,
                                    ScreeningTrigger trigger, ScreeningOutcome outcome) {
        ScreeningCheck c = new ScreeningCheck();
        c.checkId        = UUID.randomUUID();
        c.prospectId     = prospectId;
        c.partyId        = subject == null ? null : subject.partyId();
        c.trigger        = trigger;
        c.result         = outcome.status();
        c.candidateCount = outcome.candidates().size();
        c.checkedAt      = Instant.now();

        if (!outcome.candidates().isEmpty()) {
            MatchCandidate top = outcome.candidates().get(0);
            c.strongestReason     = top.reason().name();
            c.strongestListedName = trim(top.listedName(), 400);
        }
        c.listVersions = join(outcome.listVersions());

        if (subject != null) {
            c.subjectFullName     = trim(subject.fullName(), 400);
            c.subjectCurp         = trim(subject.curp(), 18);
            c.subjectRfc          = trim(subject.rfc(), 13);
            c.subjectDateOfBirth  = subject.dateOfBirth();
        }
        return c;
    }

    private static String join(Set<UUID> versions) {
        if (versions == null || versions.isEmpty()) return null;
        return versions.stream().map(UUID::toString).reduce((a, b) -> a + "," + b).orElse(null);
    }

    private static String trim(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** Si esta consulta se hizo <b>contra alguna lista</b>. Falso significa que no había ninguna vigente. */
    public boolean hadListsToCheckAgainst() {
        return listVersions != null && !listVersions.isBlank();
    }

    public UUID getCheckId()                  { return checkId; }
    public UUID getProspectId()               { return prospectId; }
    public UUID getPartyId()                  { return partyId; }
    public ScreeningTrigger getTrigger()      { return trigger; }
    public ScreeningStatus getResult()        { return result; }
    public int getCandidateCount()            { return candidateCount; }
    public String getStrongestReason()        { return strongestReason; }
    public String getStrongestListedName()    { return strongestListedName; }
    public String getListVersions()           { return listVersions; }
    public String getSubjectFullName()        { return subjectFullName; }
    public String getSubjectCurp()            { return subjectCurp; }
    public String getSubjectRfc()             { return subjectRfc; }
    public LocalDate getSubjectDateOfBirth()  { return subjectDateOfBirth; }
    public Instant getCheckedAt()             { return checkedAt; }
}

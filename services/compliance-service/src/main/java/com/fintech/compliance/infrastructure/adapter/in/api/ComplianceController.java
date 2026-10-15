package com.fintech.compliance.infrastructure.adapter.in.api;

import com.fintech.compliance.application.port.out.RescreenRunRepository;
import com.fintech.compliance.application.service.PersonVerificationService;
import com.fintech.compliance.application.service.RescreenService;
import com.fintech.compliance.application.service.ScreeningJournal;
import com.fintech.compliance.domain.ScreeningCheck;
import com.fintech.compliance.domain.PersonVerification;
import com.fintech.compliance.domain.RescreenRun;
import com.fintech.compliance.domain.IdentityStatus;
import com.fintech.compliance.domain.ScreeningStatus;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * La cola de cumplimiento y el re-cruce.
 *
 * <p>Sin estos dos endpoints el control no es operable: cualquier homónimo —que es el caso común—
 * quedaría bloqueado para siempre, y las listas nuevas no llegarían nunca a la población viva.
 */
@RestController
@RequestMapping("/api/v1/compliance")
public class ComplianceController {

    private final PersonVerificationService verifications;
    private final RescreenService rescreen;
    private final RescreenRunRepository runs;
    private final com.fintech.compliance.application.port.out.VersionChangeRepository changes;
    private final ScreeningJournal journal;

    public ComplianceController(PersonVerificationService verifications,
                                RescreenService rescreen,
                                RescreenRunRepository runs,
                                com.fintech.compliance.application.port.out.VersionChangeRepository changes,
                                ScreeningJournal journal) {
        this.verifications = verifications;
        this.rescreen      = rescreen;
        this.runs          = runs;
        this.changes       = changes;
        this.journal       = journal;
    }

    /**
     * Evalúa a una persona <b>bajo demanda</b>.
     *
     * <h2>Por qué hace falta si el alta ya dispara la evaluación sola</h2>
     *
     * Porque el disparador es {@code origination.prospect-created}, y hay una población para la que
     * ese evento <b>ya pasó</b>: las personas que existían antes de que este control existiera. No
     * tienen verificación, y sin verificación quedan bloqueadas en todas las compuertas sin ninguna
     * forma de desbloquearse — no por una decisión, sino por una ausencia.
     *
     * <p>Es el <b>mismo caso de uso</b> que el listener, con un adaptador de entrada distinto: no
     * duplica regla ninguna. Y es <b>idempotente por prospecto</b>, así que remediar una población
     * se puede reintentar sin miedo a pisar una adjudicación ya firmada.
     */
    @PostMapping("/verifications")
    public ResponseEntity<Map<String, Object>> evaluate(
            @RequestBody @jakarta.validation.Valid EvaluatePersonRequest req) {
        PersonVerification v = verifications.evaluateOnDemand(
                new com.fintech.compliance.domain.ScreeningSubject(
                        req.partyId(), req.fullName(), req.dateOfBirth(),
                        req.curp(), req.rfc(), req.country() == null ? "MX" : req.country()),
                req.prospectId());
        return ResponseEntity.ok(toView(v));
    }

    /** El veredicto de una persona: lo que consultan las compuertas. */
    @GetMapping("/verifications/by-party/{partyId}")
    public ResponseEntity<Map<String, Object>> byParty(@PathVariable UUID partyId) {
        return verifications.byParty(partyId)
                .map(v -> ResponseEntity.ok(toView(v)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Cumplimiento despeja o confirma una coincidencia.
     *
     * @param req lleva autor y argumento, y los dos son obligatorios: un veredicto de listas sin
     *            quién lo firmó y sin por qué no sustenta nada ante una revisión
     */
    @PostMapping("/verifications/{verificationId}/adjudicate")
    public ResponseEntity<Map<String, Object>> adjudicate(@PathVariable UUID verificationId,
                                                          @RequestBody AdjudicateRequest req) {
        PersonVerification v = verifications.adjudicateScreening(
                verificationId, ScreeningStatus.valueOf(req.decision()), req.decidedBy(), req.notes());
        return ResponseEntity.ok(toView(v));
    }

    /** La verificación por prospecto: es la llave que tienen los flujos de alta. */
    @GetMapping("/verifications/by-prospect/{prospectId}")
    public ResponseEntity<Map<String, Object>> byProspect(@PathVariable UUID prospectId) {
        return verifications.byProspect(prospectId)
                .map(v -> ResponseEntity.ok(toView(v)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * El <b>historial de consultas</b> de una persona: la bitácora que pide una revisión.
     *
     * <p>Distinto de {@code /verifications/by-prospect/{id}}, que devuelve el veredicto <b>vigente</b>.
     * Esto devuelve <b>cada vez que se la consultó</b>: cuándo, por qué motivo, contra qué versiones
     * de lista y qué salió ese día. Es lo que permite contestar «el 3 de septiembre salió limpia
     * contra la lista del día 1» en vez de sólo «hoy está señalada».
     */
    @GetMapping("/verifications/by-prospect/{prospectId}/checks")
    public Map<String, Object> checkHistory(@PathVariable UUID prospectId,
                                            @RequestParam(defaultValue = "50") int limit) {
        List<ScreeningCheck> history = journal.historyOf(prospectId, Math.min(limit, 200));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("prospectId", prospectId);
        m.put("totalChecks", journal.countFor(prospectId));
        m.put("checks", history.stream().map(ComplianceController::toView).toList());
        return m;
    }

    /**
     * Un analista dictamina la <b>identidad</b>.
     *
     * <p>Existía el caso de uso y no existía esta puerta: {@code recordIdentityVerdict} no tenía un
     * solo llamador de producción, así que una identidad en {@code IN_REVIEW} —que mientras no haya
     * proveedor de KYC es <b>toda</b> identidad— no se podía resolver desde ningún sitio. La cola
     * tenía entrada y no salida.
     *
     * @param req autor y argumento obligatorios, igual que en el de listas: las dos son decisiones
     *            regulatorias sobre una persona y ninguna se sustenta sin firma
     */
    @PostMapping("/verifications/{verificationId}/identity")
    public ResponseEntity<Map<String, Object>> adjudicateIdentity(
            @PathVariable UUID verificationId,
            @RequestBody @jakarta.validation.Valid IdentityDecisionRequest req) {
        PersonVerification v = verifications.adjudicateIdentity(
                verificationId, IdentityStatus.valueOf(req.decision()), req.decidedBy(), req.notes());
        return ResponseEntity.ok(toView(v));
    }

    /**
     * Dispara un re-cruce de la población viva.
     *
     * <p>Devuelve 202 y no 200: es un lote que puede tardar, y lo que interesa al llamador es que
     * quedó registrado — el resultado se consulta en las corridas.
     */
    @PostMapping("/rescreen")
    public ResponseEntity<Map<String, Object>> rescreen(
            @RequestParam(required = false) UUID listVersionId) {
        RescreenRun run = rescreen.rescreenAll(listVersionId, RescreenService.TRIGGER_MANUAL);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(toView(run));
    }

    /**
     * Las corridas recientes.
     *
     * <p>«¿Cuándo fue el último re-cruce?» es la primera pregunta de cualquier revisión, y la que
     * dice si el control está vivo o se apagó hace meses sin que nadie lo notara.
     */
    @GetMapping("/rescreen-runs")
    public List<Map<String, Object>> recentRuns(@RequestParam(defaultValue = "20") int limit) {
        return runs.findRecent(limit).stream().map(ComplianceController::toView).toList();
    }

    /**
     * Qué cambió en una actualización de lista.
     *
     * <p>Es la pregunta que contesta «¿por qué esta persona quedó señalada el día 14?»: porque su
     * entrada se <b>añadió</b> en la versión publicada el 3. Sin esto hay que comparar dos
     * publicaciones de cientos de miles de filas a mano.
     */
    @GetMapping("/watchlists/versions/{versionId}/changes")
    public Map<String, Object> versionChanges(@PathVariable UUID versionId,
                                              @RequestParam(required = false) String type,
                                              @RequestParam(defaultValue = "100") int limit) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("versionId", versionId);
        body.put("summary",   changes.countByType(versionId));
        body.put("changes", changes.findByVersion(versionId, type, limit).stream().map(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("changeType",  c.getChangeType().name());
            m.put("listedName",  c.getListedName());
            m.put("program",     c.getProgram());
            m.put("detail",      c.getDetail());
            m.put("previousVersionId", c.getPreviousVersionId());
            return m;
        }).toList());
        return body;
    }

    // ── Vistas ───────────────────────────────────────────────────────────────────────────────

    private static Map<String, Object> toView(PersonVerification v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("verificationId",  v.getVerificationId());
        m.put("partyId",         v.getPartyId());
        // El prospecto va en la vista porque es la llave por la que consultan los flujos de alta —y
        // porque una respuesta de `/by-prospect/{id}` que no lo trae obliga a quien la lee a confiar
        // en que pidió lo que creía pedir.
        m.put("prospectId",      v.getProspectId());
        m.put("identityStatus",  v.getIdentityStatus().name());
        m.put("screeningStatus", v.getScreeningStatus().name());
        m.put("allowsToProceed", v.allowsToProceed());
        m.put("blockReason",     v.blockReason());
        m.put("listVersions",    v.getScreenedListVersions());
        m.put("decidedBy",       v.getDecidedBy());
        m.put("decisionNotes",   v.getDecisionNotes());
        return m;
    }

    private static Map<String, Object> toView(ScreeningCheck c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("checkId",        c.getCheckId());
        m.put("checkedAt",      c.getCheckedAt());
        m.put("trigger",        c.getTrigger().name());
        m.put("result",         c.getResult().name());
        m.put("candidateCount", c.getCandidateCount());
        m.put("strongestReason",     c.getStrongestReason());
        m.put("strongestListedName", c.getStrongestListedName());
        // Las versiones consultadas, y dicho en claro si no había ninguna: sin ellas el resultado
        // no es reproducible, y «no había listas» no es lo mismo que «salió limpia».
        m.put("listVersions",        c.getListVersions());
        m.put("checkedAgainstLists", c.hadListsToCheckAgainst());
        // Los datos CON LOS QUE se consultó: si la persona corrigió su nombre después, el resultado
        // de entonces sólo se explica con el nombre de entonces.
        m.put("subjectFullName",    c.getSubjectFullName());
        m.put("subjectCurp",        c.getSubjectCurp());
        m.put("subjectRfc",         c.getSubjectRfc());
        m.put("subjectDateOfBirth", c.getSubjectDateOfBirth());
        return m;
    }

    private static Map<String, Object> toView(RescreenRun r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("runId",         r.getRunId());
        m.put("trigger",       r.getTrigger());
        m.put("listVersionId", r.getTriggeredByVersionId());
        m.put("status",        r.getStatus().name());
        m.put("totalScreened", r.getTotalScreened());
        m.put("changedCount",  r.getChangedCount());
        m.put("startedAt",     r.getStartedAt());
        m.put("finishedAt",    r.getFinishedAt());
        m.put("durationMs",    r.duration() == null ? null : r.duration().toMillis());
        m.put("failureReason", r.getFailureReason());
        return m;
    }

    public record AdjudicateRequest(@NotBlank String decision,
                                    @NotBlank String decidedBy,
                                    @NotBlank String notes) {}

    /**
     * Los datos con los que se cruza a la persona.
     *
     * <p>Sólo el nombre es obligatorio además del prospecto: las listas traen lo que traen, y exigir
     * CURP o fecha para poder evaluar dejaría sin evaluar justo a quien llegó con menos datos.
     */
    public record EvaluatePersonRequest(@jakarta.validation.constraints.NotNull UUID prospectId,
                                        UUID partyId,
                                        @NotBlank String fullName,
                                        java.time.LocalDate dateOfBirth,
                                        String curp,
                                        String rfc,
                                        String country) {}

    /** {@code decision} es {@code VERIFIED} o {@code REJECTED}; el dominio rechaza lo demás. */
    public record IdentityDecisionRequest(@NotBlank String decision,
                                          @NotBlank String decidedBy,
                                          @NotBlank String notes) {}
}

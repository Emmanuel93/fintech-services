package com.fintech.scoring.application.service;

import com.fintech.scoring.application.port.out.ScoringPolicyRepository;
import com.fintech.scoring.domain.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class ScoringPolicyService {

    private static final Logger log = LoggerFactory.getLogger(ScoringPolicyService.class);

    private final ScoringPolicyRepository policyRepository;

    public ScoringPolicyService(ScoringPolicyRepository policyRepository) {
        this.policyRepository = policyRepository;
    }

    @Transactional
    public ScoringPolicy create(String prospectType, String productTypeIntent,
                                String name, String description,
                                List<RuleSpec> rules, List<ThresholdSpec> thresholds) {
        return create(prospectType, productTypeIntent, name, description, rules, thresholds, NoHistoryPolicy.DEFAULT);
    }

    /**
     * Publica la política del producto, con lo que se hace con quien no tiene historial en buró.
     *
     * <p>Se valida antes de tocar la vigente: una política que no se puede publicar no debe dejar al
     * producto sin ninguna.
     */
    @Transactional
    public ScoringPolicy create(String prospectType, String productTypeIntent,
                                String name, String description,
                                List<RuleSpec> rules, List<ThresholdSpec> thresholds,
                                NoHistoryPolicy noHistory) {
        NoHistoryPolicy sinHistorial = noHistory == null ? NoHistoryPolicy.DEFAULT : noHistory;
        validateNoHistory(sinHistorial, thresholds);
        // Sustituir la vigente, si la hay. Sólo puede haber una activa por producto, y eso lo
        // garantiza un índice único parcial en la base.
        //
        // El `flush` no es decorativo: sin él, JPA ordena el INSERT de la nueva antes del UPDATE
        // que desactiva la anterior, el índice salta y el alta muere con un error de constraint
        // filtrado hasta la consola —un 502 con SQL dentro— cuando la operación era válida.
        int siguienteVersion = 1;
        var vigente = policyRepository.findActiveBy(productTypeIntent);
        if (vigente.isPresent()) {
            ScoringPolicy previous = vigente.get();
            log.info("Sustituyendo política policyId={} v{} de {} (escrita para {})",
                    previous.getPolicyId(), previous.getVersion(), productTypeIntent,
                    previous.getProspectType());
            // La anterior no se borra: queda inactiva y con su historia. Una evaluación pasada
            // apunta a la política con que se decidió, y borrarla dejaría decisiones sin
            // justificación — que es justo lo que un auditor viene a pedir.
            previous.deactivate();
            policyRepository.save(previous);
            policyRepository.flush();
            siguienteVersion = previous.getVersion() + 1;
        }

        ScoringPolicy policy = ScoringPolicy.create(
                UUID.randomUUID(), prospectType, productTypeIntent, name, description,
                siguienteVersion, sinHistorial);

        for (RuleSpec spec : rules) {
            ScoringRule rule = ScoringRule.create(
                    UUID.randomUUID(), policy,
                    spec.ruleType(), spec.creditType(), spec.operator(),
                    spec.thresholdValue(), spec.scoreContribution(),
                    spec.disqualifying(), spec.periodMonths(), spec.description());
            policy.addRule(rule);
        }

        for (ThresholdSpec spec : thresholds) {
            RiskThreshold threshold = RiskThreshold.create(
                    UUID.randomUUID(), policy,
                    spec.riskLevel(), spec.minScore(), spec.decision());
            policy.addThreshold(threshold);
        }

        ScoringPolicy saved = policyRepository.save(policy);
        log.info("Created scoring policy policyId={} name='{}' for {}/{}",
                saved.getPolicyId(), name, prospectType, productTypeIntent);
        return saved;
    }

    /**
     * Lo que la política dice de quien no tiene historial tiene que poder cumplirse.
     *
     * <ul>
     *   <li>Sin historial <b>no se aprueba en automático</b>: no hay comportamiento de pago que lo
     *       respalde. Si el score sin historial cae en una banda de aprobación automática, la política
     *       se rechaza aquí y no en silencio a la hora de evaluar.</li>
     *   <li>Pedir datos adicionales sólo tiene sentido si alguien los va a revisar. Con un score que
     *       cae en rechazo, la petición nunca llegaría a nadie.</li>
     * </ul>
     */
    static void validateNoHistory(NoHistoryPolicy noHistory, List<ThresholdSpec> thresholds) {
        if (thresholds == null || thresholds.isEmpty()) {
            return; // la validación de bandas vacías es de la petición, no de esta regla
        }
        ThresholdSpec banda = bandFor(thresholds, noHistory.score());
        if (banda.decision() == ScoringDecision.AUTO_APPROVED) {
            throw new InvalidScoringPolicyException("Sin historial en buró no se aprueba en automático: con score "
                    + noHistory.score() + " cae en " + banda.riskLevel() + " (aprobación automática). "
                    + "Baja el score sin historial a la banda de revisión y, si hace falta, pide datos adicionales.");
        }
        if (banda.decision() == ScoringDecision.REJECTED && !noHistory.requirements().isEmpty()) {
            throw new InvalidScoringPolicyException("Los datos adicionales sólo se piden si alguien los revisa: con score "
                    + noHistory.score() + " quien no tiene historial cae en " + banda.riskLevel()
                    + " (rechazo). Sube el score sin historial a la banda de revisión o quita los datos adicionales.");
        }
    }

    /** La banda que le toca a un score: la de mayor mínimo que alcanza, o la más baja si no alcanza ninguna. */
    static ThresholdSpec bandFor(List<ThresholdSpec> thresholds, int score) {
        return thresholds.stream()
                .sorted(Comparator.comparingInt(ThresholdSpec::minScore).reversed())
                .filter(t -> score >= t.minScore())
                .findFirst()
                .orElseGet(() -> thresholds.stream().min(Comparator.comparingInt(ThresholdSpec::minScore)).orElseThrow());
    }

    @Transactional
    public Optional<ScoringPolicy> deactivate(UUID policyId) {
        return policyRepository.findById(policyId).map(p -> {
            p.deactivate();
            return policyRepository.save(p);
        });
    }

    /**
     * Read within a transaction and force-initialize both lazy collections (rules + thresholds)
     * before the entities detach — open-in-view is off and the response DTO maps both, so mapping
     * them after the session closes would throw LazyInitializationException. Two @OneToMany bags
     * can't both be EAGER (MultipleBagFetchException), hence the in-session initialization here.
     */
    @Transactional(readOnly = true)
    public List<ScoringPolicy> listActive() {
        List<ScoringPolicy> policies = policyRepository.findAllActive();
        policies.forEach(ScoringPolicyService::initializeCollections);
        return policies;
    }

    @Transactional(readOnly = true)
    public Optional<ScoringPolicy> findById(UUID policyId) {
        return policyRepository.findById(policyId).map(p -> {
            initializeCollections(p);
            return p;
        });
    }

    private static void initializeCollections(ScoringPolicy policy) {
        policy.getRules().size();
        policy.getThresholds().size();
    }

    // ── Spec records (DTO → domain bridge) ───────────────────────────────────

    public record RuleSpec(
            RuleType ruleType, String creditType, RuleOperator operator,
            BigDecimal thresholdValue, int scoreContribution,
            boolean disqualifying, Integer periodMonths, String description) {}

    public record ThresholdSpec(RiskLevel riskLevel, int minScore, ScoringDecision decision) {}
}

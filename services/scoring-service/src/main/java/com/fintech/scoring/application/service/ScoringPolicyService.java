package com.fintech.scoring.application.service;

import com.fintech.scoring.application.port.out.ScoringPolicyRepository;
import com.fintech.scoring.domain.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
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
        // Sustituir la vigente, si la hay. Sólo puede haber una activa por producto, y eso lo
        // garantiza un índice único parcial en la base.
        //
        // El `flush` no es decorativo: sin él, JPA ordena el INSERT de la nueva antes del UPDATE
        // que desactiva la anterior, el índice salta y el alta muere con un error de constraint
        // filtrado hasta la consola —un 502 con SQL dentro— cuando la operación era válida.
        int siguienteVersion = 1;
        var vigente = policyRepository.findActiveBy(productTypeIntent);
        if (vigente.isPresent()) {
            ScoringPolicy anterior = vigente.get();
            log.info("Sustituyendo política policyId={} v{} de {} (escrita para {})",
                    anterior.getPolicyId(), anterior.getVersion(), productTypeIntent,
                    anterior.getProspectType());
            // La anterior no se borra: queda inactiva y con su historia. Una evaluación pasada
            // apunta a la política con que se decidió, y borrarla dejaría decisiones sin
            // justificación — que es justo lo que un auditor viene a pedir.
            anterior.deactivate();
            policyRepository.save(anterior);
            policyRepository.flush();
            siguienteVersion = anterior.getVersion() + 1;
        }

        ScoringPolicy policy = ScoringPolicy.create(
                UUID.randomUUID(), prospectType, productTypeIntent, name, description,
                siguienteVersion);

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

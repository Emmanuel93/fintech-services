package com.fintech.scoring.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "scoring_policies", schema = "scoring")
public class ScoringPolicy {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID policyId;

    /**
     * Para quién se escribió la política. <b>Descriptivo</b>: desde la migración 013 no forma parte
     * de la llave de búsqueda —la política se encuentra por producto—. Se conserva porque es parte
     * de la historia de las decisiones ya tomadas con ella.
     */
    @Column(updatable = false, length = 20)
    private String prospectType;

    @Column(nullable = false, updatable = false, length = 30)
    private String productTypeIntent;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false)
    private boolean active;

    @Column(nullable = false, updatable = false)
    private int version;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "policy", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ScoringRule> rules = new ArrayList<>();

    @OneToMany(mappedBy = "policy", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<RiskThreshold> thresholds = new ArrayList<>();

    protected ScoringPolicy() {}

    public static ScoringPolicy create(UUID policyId, String prospectType, String productTypeIntent,
                                       String name, String description) {
        return create(policyId, prospectType, productTypeIntent, name, description, 1);
    }

    /**
     * Con versión explícita: se usa al sustituir una política vigente.
     *
     * <p>La versión va en el constructor y no en un setter a propósito. Una política es el
     * criterio con que se decidió un crédito, y las evaluaciones pasadas apuntan a ella; dejar la
     * versión mutable permitiría renumerar a posteriori lo que ya se usó para decidir, que es
     * exactamente lo que un auditor no debe poder encontrar.
     */
    public static ScoringPolicy create(UUID policyId, String prospectType, String productTypeIntent,
                                       String name, String description, int version) {
        ScoringPolicy p = new ScoringPolicy();
        p.policyId          = policyId;
        p.prospectType      = prospectType;
        p.productTypeIntent = productTypeIntent;
        p.name              = name;
        p.description       = description;
        p.active            = true;
        p.version           = version;
        p.createdAt         = Instant.now();
        return p;
    }

    public void addRule(ScoringRule rule) {
        rules.add(rule);
    }

    public void addThreshold(RiskThreshold threshold) {
        thresholds.add(threshold);
    }

    public void deactivate() {
        this.active = false;
    }

    public UUID getPolicyId()           { return policyId; }
    public String getProspectType()     { return prospectType; }
    public String getProductTypeIntent(){ return productTypeIntent; }
    public String getName()             { return name; }
    public String getDescription()      { return description; }
    public boolean isActive()           { return active; }
    public int getVersion()             { return version; }
    public Instant getCreatedAt()       { return createdAt; }
    public List<ScoringRule>     getRules()      { return Collections.unmodifiableList(rules); }
    public List<RiskThreshold>   getThresholds() { return Collections.unmodifiableList(thresholds); }
}

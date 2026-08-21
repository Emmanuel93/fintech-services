package com.fintech.configuration.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(schema = "configuration", name = "config_parameters")
public class ConfigParameter {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "param_key", nullable = false, length = 100)
    private String paramKey;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String value;

    @Column(name = "product_type", length = 50)
    private String productType;

    @Column(name = "channel_type", length = 50)
    private String channelType;

    @Column(nullable = false)
    private int version;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ConfigParameterStatus status;

    @Column(name = "effective_date")
    private LocalDate effectiveDate;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "approved_by")
    private UUID approvedBy;

    @Column(name = "previous_version_ref")
    private UUID previousVersionRef;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ConfigParameter() {}

    public static ConfigParameter create(String paramKey, String value,
                                         String productType, String channelType,
                                         LocalDate effectiveDate, UUID createdBy,
                                         UUID previousVersionRef, int version) {
        ConfigParameter p = new ConfigParameter();
        p.id = UUID.randomUUID();
        p.paramKey = paramKey;
        p.value = value;
        p.productType = productType;
        p.channelType = channelType;
        p.version = version;
        p.status = ConfigParameterStatus.PENDING_APPROVAL;
        p.effectiveDate = effectiveDate;
        p.createdBy = createdBy;
        p.approvedBy = null;
        p.previousVersionRef = previousVersionRef;
        p.createdAt = Instant.now();
        p.updatedAt = p.createdAt;
        return p;
    }

    /** Checker approves: PENDING_APPROVAL → ACTIVE. */
    public void approve(UUID approverId) {
        if (status != ConfigParameterStatus.PENDING_APPROVAL) {
            throw new InvalidConfigStateTransitionException(id, status, ConfigParameterStatus.ACTIVE);
        }
        this.approvedBy = approverId;
        this.status = ConfigParameterStatus.ACTIVE;
        this.updatedAt = Instant.now();
    }

    /** Checker rejects: PENDING_APPROVAL → DRAFT. */
    public void reject() {
        if (status != ConfigParameterStatus.PENDING_APPROVAL) {
            throw new InvalidConfigStateTransitionException(id, status, ConfigParameterStatus.DRAFT);
        }
        this.status = ConfigParameterStatus.DRAFT;
        this.updatedAt = Instant.now();
    }

    /** Deprecate an ACTIVE parameter (CF-02: never delete). */
    public void deprecate() {
        if (status != ConfigParameterStatus.ACTIVE) {
            throw new InvalidConfigStateTransitionException(id, status, ConfigParameterStatus.DEPRECATED);
        }
        this.status = ConfigParameterStatus.DEPRECATED;
        this.updatedAt = Instant.now();
    }

    public UUID getId()                   { return id; }
    public String getParamKey()           { return paramKey; }
    public String getValue()              { return value; }
    public String getProductType()        { return productType; }
    public String getChannelType()        { return channelType; }
    public int getVersion()               { return version; }
    public ConfigParameterStatus getStatus() { return status; }
    public LocalDate getEffectiveDate()   { return effectiveDate; }
    public UUID getCreatedBy()            { return createdBy; }
    public UUID getApprovedBy()           { return approvedBy; }
    public UUID getPreviousVersionRef()   { return previousVersionRef; }
    public Instant getCreatedAt()         { return createdAt; }
    public Instant getUpdatedAt()         { return updatedAt; }
}

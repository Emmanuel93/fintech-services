package com.fintech.compliance.domain;

import jakarta.persistence.*;

import java.util.UUID;

/** Una entrada que cambió respecto a la versión anterior de la misma lista. */
@Entity
@Table(name = "watchlist_version_changes", schema = "compliance")
public class WatchlistVersionChange {

    @Id
    @Column(name = "change_id", nullable = false, updatable = false)
    private UUID changeId;

    @Column(name = "version_id", nullable = false, updatable = false)
    private UUID versionId;

    /** Contra cuál se comparó. Nulo sólo en {@code INITIAL_LOAD}, que no compara con nada. */
    @Column(name = "previous_version_id", updatable = false)
    private UUID previousVersionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "change_type", length = 20, nullable = false, updatable = false)
    private VersionChangeType changeType;

    @Column(name = "identity_key", length = 64, updatable = false)
    private String identityKey;

    /** El nombre tal como lo publicó la lista: es lo que una persona reconoce al revisar. */
    @Column(name = "listed_name", length = 400, updatable = false)
    private String listedName;

    @Column(name = "program", length = 200, updatable = false)
    private String program;

    @Column(name = "detail", length = 400, updatable = false)
    private String detail;

    protected WatchlistVersionChange() { }

    public static WatchlistVersionChange initialLoad(UUID versionId, int entries) {
        WatchlistVersionChange c = new WatchlistVersionChange();
        c.changeId   = UUID.randomUUID();
        c.versionId  = versionId;
        c.changeType = VersionChangeType.INITIAL_LOAD;
        c.detail     = "primera carga de esta lista: " + entries + " entradas";
        return c;
    }

    public UUID getChangeId()              { return changeId; }
    public UUID getVersionId()             { return versionId; }
    public UUID getPreviousVersionId()     { return previousVersionId; }
    public VersionChangeType getChangeType() { return changeType; }
    public String getIdentityKey()         { return identityKey; }
    public String getListedName()          { return listedName; }
    public String getProgram()             { return program; }
    public String getDetail()              { return detail; }
}

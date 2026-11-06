package com.fintech.stp.domain.collection;

import com.fintech.shared.time.BusinessClock;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A person's collection CLABE: the account they pay their loans to. One per person and company, never recycled.
 *
 * <p>Aggregate root. The CLABE identifies who paid; the numeric reference of each transfer identifies the loan.
 */
@Entity
@Table(schema = "stp", name = "collection_accounts")
public class CollectionAccount {

    @Id
    @Column(name = "collection_account_id", nullable = false, updatable = false)
    private UUID collectionAccountId;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "party_id", nullable = false, updatable = false)
    private UUID partyId;

    @Column(name = "clabe", nullable = false, updatable = false, length = 18)
    private String clabe;

    @Column(name = "holder_name")
    private String holderName;

    @Column(name = "tax_id")
    private String taxId;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "status_reason")
    private String statusReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CollectionAccount() {
    }

    public static CollectionAccount issue(UUID companyId, UUID partyId, CollectionClabe clabe,
                                          String holderName, String taxId) {
        CollectionAccount account = new CollectionAccount();
        account.collectionAccountId = UUID.randomUUID();
        account.companyId = Objects.requireNonNull(companyId, "companyId");
        account.partyId = Objects.requireNonNull(partyId, "partyId");
        account.clabe = Objects.requireNonNull(clabe, "clabe").value();
        account.holderName = holderName;
        account.taxId = taxId;
        account.status = CollectionAccountStatus.ACTIVE.name();
        account.createdAt = BusinessClock.current().now();
        account.updatedAt = account.createdAt;
        return account;
    }

    /** Stops receiving credits: they are returned with cause 2 until it is reactivated. */
    public void block(String reason) {
        requireNotClosed();
        transition(CollectionAccountStatus.BLOCKED, reason);
    }

    public void reactivate(String reason) {
        requireNotClosed();
        transition(CollectionAccountStatus.ACTIVE, reason);
    }

    /** For good: credits are returned with cause 3, and the CLABE is never given to anyone else. */
    public void close(String reason) {
        transition(CollectionAccountStatus.CLOSED, reason);
    }

    private void requireNotClosed() {
        if (status() == CollectionAccountStatus.CLOSED) {
            throw new IllegalStateException("La CLABE de cobro " + clabe + " está cerrada y no se reabre");
        }
    }

    private void transition(CollectionAccountStatus to, String reason) {
        this.status = to.name();
        this.statusReason = reason;
        this.updatedAt = BusinessClock.current().now();
    }

    public CollectionAccountStatus status() {
        return CollectionAccountStatus.valueOf(status);
    }

    public boolean isActive() {
        return status() == CollectionAccountStatus.ACTIVE;
    }

    public UUID getCollectionAccountId() { return collectionAccountId; }
    public UUID getCompanyId() { return companyId; }
    public UUID getPartyId() { return partyId; }
    public String getClabe() { return clabe; }
    public String getHolderName() { return holderName; }
    public String getTaxId() { return taxId; }
    public String getStatusReason() { return statusReason; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}

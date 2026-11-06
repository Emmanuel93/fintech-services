package com.fintech.salesorg.domain;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The placement policy of a unit: what a distributor under it may place to each kind of client.
 *
 * <p>Only the distributor line has one. A direct credit's amount and term come from the score in its origination
 * offer; a distributor's clients do not pass through the score, so the branch bounds them here, per client type.
 * It lives in the tree like the VAT rate: declared at a branch (or higher) and inherited by everything below;
 * a distributor may declare her own, stricter one.
 */
public record PlacementPolicy(Map<ClientType, ClientLimits> limits) {

    public static final String PRODUCT_TYPE = "DISTRIBUTOR_LINE";

    public static final PlacementPolicy EMPTY = new PlacementPolicy(Map.of());

    public PlacementPolicy {
        Map<ClientType, ClientLimits> copy = new EnumMap<>(ClientType.class);
        if (limits != null) limits.forEach((k, v) -> { if (v != null && !v.isEmpty()) copy.put(k, v); });
        limits = Map.copyOf(copy);
    }

    public static PlacementPolicy of(ClientLimits nuevo, ClientLimits revolvente) {
        Map<ClientType, ClientLimits> m = new EnumMap<>(ClientType.class);
        if (nuevo != null) m.put(ClientType.NUEVO, nuevo);
        if (revolvente != null) m.put(ClientType.REVOLVENTE, revolvente);
        return new PlacementPolicy(m);
    }

    public ClientLimits forType(ClientType type) {
        return limits.getOrDefault(type, ClientLimits.NONE);
    }

    public boolean isEmpty() {
        return limits.isEmpty();
    }

    /** Resolved for a placement: both client types fully bounded. */
    public boolean isComplete() {
        for (ClientType t : ClientType.values()) if (!forType(t).isComplete()) return false;
        return true;
    }

    /** The child's declared limits replace the inherited ones, type by type and field by field. */
    public PlacementPolicy overriddenBy(PlacementPolicy child) {
        if (child == null || child.isEmpty()) return this;
        Map<ClientType, ClientLimits> m = new EnumMap<>(ClientType.class);
        for (ClientType t : ClientType.values()) m.put(t, forType(t).overriddenBy(child.forType(t)));
        return new PlacementPolicy(m);
    }

    /** The stricter of the two, field by field: how a distributor's own policy combines with her branch's. */
    public PlacementPolicy tightenedBy(PlacementPolicy child) {
        if (child == null || child.isEmpty()) return this;
        Map<ClientType, ClientLimits> m = new EnumMap<>(ClientType.class);
        for (ClientType t : ClientType.values()) m.put(t, forType(t).tightenedBy(child.forType(t)));
        return new PlacementPolicy(m);
    }

    /** What this policy allows beyond the inherited one; a distributor may not declare any of it. */
    public List<String> widerThan(PlacementPolicy inherited) {
        List<String> wider = new ArrayList<>();
        for (ClientType t : ClientType.values()) {
            for (String w : forType(t).widerThan(inherited.forType(t))) wider.add(t + ": " + w);
        }
        return wider;
    }

    public List<String> violations(ProductEnvelope product) {
        List<String> errors = new ArrayList<>();
        for (ClientType t : ClientType.values()) {
            for (String e : forType(t).violations(product)) errors.add(t + ": " + e);
        }
        return errors;
    }
}

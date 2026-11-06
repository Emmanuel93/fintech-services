package com.fintech.salesorg.application.service;

import com.fintech.salesorg.application.EffectivePlacementPolicy;
import com.fintech.salesorg.application.port.in.PlacementPolicyUseCase;
import com.fintech.salesorg.application.port.out.OrgLevelRepository;
import com.fintech.salesorg.application.port.out.OrgUnitRepository;
import com.fintech.salesorg.application.port.out.PlacementPolicyEventPublisher;
import com.fintech.salesorg.application.port.out.PlacementPolicyRepository;
import com.fintech.salesorg.application.port.out.ProductEnvelopePort;
import com.fintech.salesorg.domain.ClientLimits;
import com.fintech.salesorg.domain.ClientType;
import com.fintech.salesorg.domain.InvalidPlacementPolicyException;
import com.fintech.salesorg.domain.OrgLevel;
import com.fintech.salesorg.domain.OrgUnit;
import com.fintech.salesorg.domain.OrgUnitNotFoundException;
import com.fintech.salesorg.domain.PlacementPolicy;
import com.fintech.salesorg.domain.ProductEnvelope;
import com.fintech.salesorg.domain.UnitPlacementRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The placement policy of the distributor line, resolved down the tree.
 *
 * <p>Resolution walks the materialized path from the root to the unit: a structural unit (national, region, zone,
 * branch, executive) overrides the limits it declares; a DISTRIBUTOR node can only tighten what reaches it, so her own
 * numbers combine as the stricter of each. A policy is validated against the catalog's line when saved: the branch
 * narrows the product's envelope, it never widens it.
 *
 * <p>Every change is published for the whole subtree it touches, one message per unit, already resolved: the
 * portfolio keeps a copy per unit and per distributor and authorizes placements against it without a call.
 */
@Service
@Transactional
public class PlacementPolicyService implements PlacementPolicyUseCase {

    private static final Logger log = LoggerFactory.getLogger(PlacementPolicyService.class);

    static final String DISTRIBUTOR_LEVEL = "DISTRIBUTOR";

    private final OrgUnitRepository units;
    private final OrgLevelRepository levels;
    private final PlacementPolicyRepository rules;
    private final ProductEnvelopePort products;
    private final PlacementPolicyEventPublisher publisher;

    public PlacementPolicyService(OrgUnitRepository units, OrgLevelRepository levels, PlacementPolicyRepository rules,
                                  ProductEnvelopePort products, PlacementPolicyEventPublisher publisher) {
        this.units = units;
        this.levels = levels;
        this.rules = rules;
        this.products = products;
        this.publisher = publisher;
    }

    @Override
    public EffectivePlacementPolicy declare(String unitCode, PlacementPolicy policy, String staffUserId) {
        if (policy == null || policy.isEmpty()) return clear(unitCode, staffUserId);
        OrgUnit unit = unitOf(unitCode);

        ProductEnvelope product = products.distributorLine().orElseThrow(() -> new InvalidPlacementPolicyException(
                "El catálogo no tiene una línea de distribuidora activa: no hay producto contra el cual validar la política"));
        List<String> violations = policy.violations(product);
        if (!violations.isEmpty()) {
            throw new InvalidPlacementPolicyException("La política no cabe en el producto " + product.productCode()
                    + ": " + String.join("; ", violations));
        }
        if (isDistributor(unit, levelCodes())) {
            // Her free will goes one way: she may be stricter than her branch, never wider.
            PlacementPolicy inherited = resolveAbove(unit).effective();
            List<String> wider = policy.widerThan(inherited);
            if (!wider.isEmpty()) {
                throw new InvalidPlacementPolicyException("Una distribuidora sólo puede ser más estricta que su sucursal: "
                        + String.join("; ", wider));
            }
        }

        Map<ClientType, UnitPlacementRule> existing = rules.findByUnitId(unit.getUnitId()).stream()
                .collect(Collectors.toMap(UnitPlacementRule::getClientType, Function.identity(), (a, b) -> a, () -> new EnumMap<>(ClientType.class)));
        List<UnitPlacementRule> toSave = new ArrayList<>();
        List<UnitPlacementRule> toDelete = new ArrayList<>();
        for (ClientType type : ClientType.values()) {
            var limits = policy.forType(type);
            UnitPlacementRule row = existing.get(type);
            if (limits.isEmpty()) {
                if (row != null) toDelete.add(row);
            } else if (row == null) {
                toSave.add(UnitPlacementRule.of(unit.getUnitId(), type, limits, staffUserId));
            } else {
                row.apply(limits, staffUserId);
                toSave.add(row);
            }
        }
        if (!toDelete.isEmpty()) rules.deleteAll(toDelete);
        if (!toSave.isEmpty()) rules.saveAll(toSave);
        log.info("Política de colocación de {} declarada por {}: {}", unitCode, staffUserId, policy.limits());

        republish(unit);
        return effective(unitCode);
    }

    @Override
    public EffectivePlacementPolicy clear(String unitCode, String staffUserId) {
        OrgUnit unit = unitOf(unitCode);
        List<UnitPlacementRule> own = rules.findByUnitId(unit.getUnitId());
        if (!own.isEmpty()) {
            rules.deleteAll(own);
            log.info("Política de colocación de {} retirada por {}: vuelve a heredar", unitCode, staffUserId);
        }
        republish(unit);
        return effective(unitCode);
    }

    @Override
    @Transactional(readOnly = true)
    public PlacementPolicy declared(String unitCode) {
        return policyOf(rules.findByUnitId(unitOf(unitCode).getUnitId()));
    }

    @Override
    @Transactional(readOnly = true)
    public EffectivePlacementPolicy effective(String unitCode) {
        OrgUnit unit = unitOf(unitCode);
        List<OrgUnit> chain = units.findAncestorsInclusive(unit.getPath());
        return resolve(unit, chain, policiesOf(chain), levelCodes());
    }

    @Override
    public void republish(OrgUnit unit) {
        List<OrgUnit> ancestors = units.findAncestorsInclusive(unit.getPath());
        List<OrgUnit> subtree = units.findSubtree(unit.getPath());
        // Everything the resolution needs, read once: the chain above the unit and the whole branch below it.
        Map<String, OrgUnit> byPath = new LinkedHashMap<>();
        ancestors.forEach(u -> byPath.put(u.getPath(), u));
        subtree.forEach(u -> byPath.put(u.getPath(), u));
        Map<UUID, PlacementPolicy> declared = policiesOf(byPath.values());
        Map<UUID, String> levelCodes = levelCodes();
        int published = 0;
        for (OrgUnit member : subtree) {
            List<OrgUnit> chain = new ArrayList<>();
            for (String prefix : prefixesOf(member.getPath())) {
                OrgUnit u = byPath.get(prefix);
                if (u != null) chain.add(u);
            }
            publisher.publishChanged(resolve(member, chain, declared, levelCodes));
            published++;
        }
        log.info("Política de colocación publicada para {} unidades bajo {}", published, unit.getCode());
    }

    // ── Resolution ───────────────────────────────────────────────────────────────────────────────

    /** What reaches the unit from above, without its own declaration. */
    private EffectivePlacementPolicy resolveAbove(OrgUnit unit) {
        List<OrgUnit> chain = units.findAncestorsInclusive(unit.getPath()).stream()
                .filter(u -> !u.getUnitId().equals(unit.getUnitId()))
                .toList();
        return resolve(unit, chain, policiesOf(chain), levelCodes());
    }

    /**
     * Folds the declared policies from the root down to the unit. Structural units override; a distributor tightens.
     *
     * @param chain the units on the path, root first (the unit itself last when its own declaration counts)
     */
    static EffectivePlacementPolicy resolve(OrgUnit unit, List<OrgUnit> chain, Map<UUID, PlacementPolicy> declared,
                                            Map<UUID, String> levelCodes) {
        PlacementPolicy effective = PlacementPolicy.EMPTY;
        String inheritedFrom = null;
        for (OrgUnit u : chain.stream().sorted((a, b) -> Integer.compare(depthOf(a), depthOf(b))).toList()) {
            PlacementPolicy own = declared.getOrDefault(u.getUnitId(), PlacementPolicy.EMPTY);
            if (own.isEmpty()) continue;
            effective = isDistributor(u, levelCodes) ? effective.tightenedBy(own) : effective.overriddenBy(own);
            inheritedFrom = u.getCode();
        }
        return new EffectivePlacementPolicy(unit.getUnitId(), unit.getCode(), unit.getPath(),
                levelCodes.get(unit.getLevelId()), unit.getPartyRef(),
                declared.getOrDefault(unit.getUnitId(), PlacementPolicy.EMPTY), effective, inheritedFrom);
    }

    private static int depthOf(OrgUnit u) {
        return u.getPath().length() - u.getPath().replace(".", "").length();
    }

    private static boolean isDistributor(OrgUnit unit, Map<UUID, String> levelCodes) {
        return DISTRIBUTOR_LEVEL.equals(levelCodes.get(unit.getLevelId()));
    }

    /** {@code MX.R.Z.S} → {@code [MX, MX.R, MX.R.Z, MX.R.Z.S]}. */
    static List<String> prefixesOf(String path) {
        List<String> prefixes = new ArrayList<>();
        int from = 0;
        while (true) {
            int dot = path.indexOf('.', from);
            if (dot < 0) {
                prefixes.add(path);
                return prefixes;
            }
            prefixes.add(path.substring(0, dot));
            from = dot + 1;
        }
    }

    private Map<UUID, PlacementPolicy> policiesOf(Collection<OrgUnit> unitsInPlay) {
        if (unitsInPlay.isEmpty()) return Map.of();
        Map<UUID, List<UnitPlacementRule>> byUnit = rules.findByUnitIds(unitsInPlay.stream().map(OrgUnit::getUnitId).toList())
                .stream().collect(Collectors.groupingBy(UnitPlacementRule::getUnitId));
        Map<UUID, PlacementPolicy> policies = new HashMap<>();
        byUnit.forEach((id, rows) -> policies.put(id, policyOf(rows)));
        return policies;
    }

    static PlacementPolicy policyOf(List<UnitPlacementRule> rows) {
        Map<ClientType, ClientLimits> m = new EnumMap<>(ClientType.class);
        rows.forEach(r -> m.put(r.getClientType(), r.limits()));
        return new PlacementPolicy(m);
    }

    private Map<UUID, String> levelCodes() {
        return levels.findAllOrderByDepthAsc().stream()
                .collect(Collectors.toMap(OrgLevel::getLevelId, OrgLevel::getCode, (a, b) -> a));
    }

    private OrgUnit unitOf(String code) {
        return units.findByCode(code).orElseThrow(() -> new OrgUnitNotFoundException(code));
    }
}

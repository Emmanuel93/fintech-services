package com.fintech.creditportfolio.application.service;

import com.fintech.creditportfolio.application.port.out.PlacementPolicyCopyRepository;
import com.fintech.creditportfolio.domain.CreditAccount;
import com.fintech.creditportfolio.domain.beneficiary.ClientLimits;
import com.fintech.creditportfolio.domain.beneficiary.PlacementPolicyCopy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Keeps the portfolio's copy of the placement policies and finds the one that applies to a line.
 *
 * <p>The holder's own node first: a DISTRIBUTOR node is published with her {@code partyRef}, and that copy already
 * folds her stricter policy with her branch's. Otherwise the branch the line was originated in. A line with neither
 * places under the product alone, as before the policy existed.
 */
@Service
@Transactional
public class PlacementPolicyCopyService {

    private static final Logger log = LoggerFactory.getLogger(PlacementPolicyCopyService.class);

    private final PlacementPolicyCopyRepository copies;

    public PlacementPolicyCopyService(PlacementPolicyCopyRepository copies) {
        this.copies = copies;
    }

    public PlacementPolicyCopy upsert(String unitCode, UUID unitId, String path, String levelCode, UUID partyRef,
                                      boolean configured, String inheritedFrom, ClientLimits nuevo, ClientLimits revolvente,
                                      String eventId, Instant occurredOn) {
        PlacementPolicyCopy copy = copies.findByUnitCode(unitCode).orElseGet(() -> PlacementPolicyCopy.of(unitCode));
        // Events of one unit arrive in order (keyed by its code); an older redelivery must not undo a newer copy.
        if (copy.getUpdatedAt() != null && occurredOn != null && copy.getEventId() != null && occurredOn.isBefore(copy.getUpdatedAt())) {
            log.info("placement-policy-changed de {} más vieja que la copia ({} < {}): se ignora", unitCode, occurredOn, copy.getUpdatedAt());
            return copy;
        }
        copy.overwrite(unitId, path, levelCode, partyRef, configured, inheritedFrom, nuevo, revolvente, eventId, occurredOn);
        PlacementPolicyCopy saved = copies.save(copy);
        log.info("Política de colocación copiada unidad={} nivel={} configurada={} heredaDe={}", unitCode, levelCode, configured, inheritedFrom);
        return saved;
    }

    /** The policy that applies to a line: her own node's copy, else her origin branch's; empty when none is configured. */
    @Transactional(readOnly = true)
    public Optional<PlacementPolicyCopy> forAccount(CreditAccount line) {
        Optional<PlacementPolicyCopy> own = line.getObligorPartyId() == null ? Optional.empty()
                : copies.findByPartyRef(line.getObligorPartyId()).filter(PlacementPolicyCopy::isConfigured);
        if (own.isPresent()) return own;
        if (line.getOriginUnitCode() == null || line.getOriginUnitCode().isBlank()) return Optional.empty();
        return copies.findByUnitCode(line.getOriginUnitCode()).filter(PlacementPolicyCopy::isConfigured);
    }
}

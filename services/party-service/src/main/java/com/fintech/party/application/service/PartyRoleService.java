package com.fintech.party.application.service;

import com.fintech.party.application.port.out.PartyEventPublisher;
import com.fintech.party.application.port.out.PartyRepository;
import com.fintech.party.application.port.out.PartyRoleRepository;
import com.fintech.party.domain.PartyNotFoundException;
import com.fintech.party.domain.PartyRole;
import com.fintech.party.domain.PartyRoleType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Roles adicionales de un party (I-03). El distribuidor es un party con el rol DISTRIBUTOR; su
 * posición en la matriz de escalado la lleva sales-org (que consume {@code party.role-granted/revoked}).
 */
@Service
@Transactional
public class PartyRoleService {

    private static final Logger log = LoggerFactory.getLogger(PartyRoleService.class);

    private final PartyRoleRepository roleRepository;
    private final PartyRepository partyRepository;
    private final PartyEventPublisher eventPublisher;

    public PartyRoleService(PartyRoleRepository roleRepository,
                            PartyRepository partyRepository,
                            PartyEventPublisher eventPublisher) {
        this.roleRepository  = roleRepository;
        this.partyRepository = partyRepository;
        this.eventPublisher  = eventPublisher;
    }

    /** Otorga un rol. Idempotente: si ya está vigente, devuelve el existente sin re-emitir evento. */
    public PartyRole grant(UUID partyId, PartyRoleType roleType, String grantedBy) {
        if (partyRepository.findById(partyId).isEmpty()) {
            throw new PartyNotFoundException(partyId);
        }
        return roleRepository.findActiveByPartyIdAndRoleType(partyId, roleType)
                .orElseGet(() -> {
                    PartyRole saved = roleRepository.save(PartyRole.grant(partyId, roleType, grantedBy));
                    log.info("Party role granted partyId={} role={} by={}", partyId, roleType, grantedBy);
                    eventPublisher.publishRoleGranted(partyId, roleType.name(), grantedBy);
                    return saved;
                });
    }

    /** Revoca el rol vigente. Devuelve true si había uno que cerrar. */
    public boolean revoke(UUID partyId, PartyRoleType roleType) {
        return roleRepository.findActiveByPartyIdAndRoleType(partyId, roleType)
                .map(role -> {
                    role.revoke();
                    roleRepository.save(role);
                    log.info("Party role revoked partyId={} role={}", partyId, roleType);
                    eventPublisher.publishRoleRevoked(partyId, roleType.name());
                    return true;
                })
                .orElse(false);
    }

    @Transactional(readOnly = true)
    public List<PartyRole> listActive(UUID partyId) {
        return roleRepository.findActiveByPartyId(partyId);
    }

    @Transactional(readOnly = true)
    public List<UUID> partyIdsWithRole(PartyRoleType roleType) {
        return roleRepository.findActivePartyIdsByRole(roleType);
    }
}

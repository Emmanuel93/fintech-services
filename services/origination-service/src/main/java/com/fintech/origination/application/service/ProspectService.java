package com.fintech.origination.application.service;

import com.fintech.origination.application.RegisterProspectCommand;
import com.fintech.origination.application.RegisterProspectResult;
import com.fintech.origination.application.port.in.FindProspectUseCase;
import com.fintech.origination.application.port.in.RegisterProspectUseCase;
import com.fintech.origination.application.port.out.ProspectEventPublisher;
import com.fintech.origination.application.port.out.ProspectRepository;
import com.fintech.origination.domain.DuplicateProspectException;
import com.fintech.origination.domain.Prospect;
import com.fintech.origination.domain.ProspectAddress;
import com.fintech.origination.domain.ProspectNotFoundException;
import com.fintech.origination.domain.event.ProspectCreatedEvent;
import com.fintech.origination.infrastructure.config.OriginationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class ProspectService implements RegisterProspectUseCase, FindProspectUseCase {

    private static final Logger log = LoggerFactory.getLogger(ProspectService.class);

    private final ProspectRepository prospectRepository;
    private final ProspectEventPublisher eventPublisher;
    private final OriginationProperties properties;

    public ProspectService(ProspectRepository prospectRepository,
                           ProspectEventPublisher eventPublisher,
                           OriginationProperties properties) {
        this.prospectRepository = prospectRepository;
        this.eventPublisher     = eventPublisher;
        this.properties         = properties;
    }

    @Override
    public RegisterProspectResult register(RegisterProspectCommand cmd) {
        log.info("Registering prospect curp={} phone={}", cmd.curp(), cmd.phone());
        if (prospectRepository.existsByCurp(cmd.curp().toUpperCase())) {
            log.warn("Duplicate prospect curp={}", cmd.curp());
            throw new DuplicateProspectException("CURP");
        }
        if (prospectRepository.existsByPhone(cmd.phone())) {
            log.warn("Duplicate prospect phone={}", cmd.phone());
            throw new DuplicateProspectException("phone");
        }

        ProspectAddress address = new ProspectAddress(
                cmd.street(), cmd.exteriorNumber(), cmd.interiorNumber(),
                cmd.neighborhood(), cmd.municipality(), cmd.city(), cmd.state(),
                cmd.postalCode(), cmd.country());

        Prospect prospect = Prospect.create(
                UUID.randomUUID(),
                cmd.prospectType() != null ? cmd.prospectType() : com.fintech.origination.domain.ProspectType.INDIVIDUAL,
                cmd.firstName(), cmd.lastName1(), cmd.lastName2(),
                cmd.curp(), cmd.rfc(),
                cmd.dateOfBirth(), cmd.gender(), cmd.stateOfBirth(),
                cmd.phone(), cmd.email(),
                address,
                cmd.channelType(),
                cmd.privacyNoticeAccepted(),
                cmd.circuloConsentAccepted(),
                cmd.documents(),
                properties.getProspectExpiryDays());

        Prospect saved = prospectRepository.save(prospect);
        log.info("Prospect persisted prospectId={} curp={}", saved.getProspectId(), saved.getCurp());

        ProspectAddress addr = saved.getAddress();
        log.info("Publishing ProspectCreatedEvent prospectId={}", saved.getProspectId());
        eventPublisher.publish(new ProspectCreatedEvent(
                saved.getProspectId(),
                saved.getProspectType(),
                saved.getFirstName(),
                saved.getLastName1(),
                saved.getLastName2(),
                saved.getCurp(),
                saved.getRfc(),
                saved.getDateOfBirth(),
                saved.getPhone(),
                saved.getEmail(),
                addr.getStreet(),
                addr.getExteriorNumber(),
                addr.getInteriorNumber(),
                addr.getNeighborhood(),
                addr.getMunicipality(),
                addr.getCity(),
                addr.getState(),
                addr.getPostalCode(),
                addr.getCountry(),
                saved.getChannelType(),
                saved.isCirculoConsentAccepted(),
                saved.getCirculoConsentAcceptedAt(),
                saved.getDocuments(),
                saved.getCreatedAt(),
                cmd.username(),
                cmd.password(),
                cmd.correlationId()));

        return new RegisterProspectResult.ProspectRegistered(
                saved.getProspectId(),
                saved.getCurp(),
                saved.getPhone(),
                saved.getCreatedAt(),
                saved.getExpiresAt());
    }

    @Override
    @Transactional(readOnly = true)
    public Prospect getById(UUID prospectId) {
        return prospectRepository.findById(prospectId)
                .orElseThrow(() -> new ProspectNotFoundException(prospectId.toString()));
    }

    @Override
    @Transactional(readOnly = true)
    public List<Prospect> findByContact(String email, String phone, String curp) {
        // Sin ningún criterio no se devuelve "todo": sería un volcado del padrón
        // de prospectos a quien sólo olvidó escribir qué buscaba.
        if (isBlank(email) && isBlank(phone) && isBlank(curp)) {
            return List.of();
        }
        return prospectRepository.findByContact(email, phone, curp);
    }

    private static boolean isBlank(String v) {
        return v == null || v.isBlank();
    }
}

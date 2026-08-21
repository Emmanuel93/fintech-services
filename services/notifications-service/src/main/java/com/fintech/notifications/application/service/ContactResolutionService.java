package com.fintech.notifications.application.service;

import com.fintech.notifications.application.port.out.ApplicationProspectLinkRepository;
import com.fintech.notifications.application.port.out.CreditAccountProgressRepository;
import com.fintech.notifications.application.port.out.PartyContactDirectoryRepository;
import com.fintech.notifications.application.port.out.ProspectContactShadowRepository;
import com.fintech.notifications.domain.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * Implementa el mecanismo de 3 pasos de T2_notifications.md §Prerequisito crítico. Ningún otro
 * servicio persiste phone/email contra un partyId — este servicio lo reconstruye 100% a partir de
 * eventos ya publicados, sin tocar ningún otro dominio.
 */
@Service
public class ContactResolutionService {

    private static final Logger log = LoggerFactory.getLogger(ContactResolutionService.class);

    private final ProspectContactShadowRepository prospectContactRepository;
    private final ApplicationProspectLinkRepository applicationLinkRepository;
    private final PartyContactDirectoryRepository partyContactRepository;
    private final CreditAccountProgressRepository progressRepository;

    public ContactResolutionService(ProspectContactShadowRepository prospectContactRepository,
                                     ApplicationProspectLinkRepository applicationLinkRepository,
                                     PartyContactDirectoryRepository partyContactRepository,
                                     CreditAccountProgressRepository progressRepository) {
        this.prospectContactRepository = prospectContactRepository;
        this.applicationLinkRepository = applicationLinkRepository;
        this.partyContactRepository = partyContactRepository;
        this.progressRepository = progressRepository;
    }

    /** Paso 1 — desde {@code origination.prospect-created}. */
    public void onProspectCreated(UUID prospectId, String firstName, String phone, String email) {
        prospectContactRepository.save(ProspectContactShadow.of(prospectId, firstName, phone, email));
    }

    /**
     * Paso 2 — desde {@code origination.offer-presented} (no {@code application-approved} como
     * describía el plan original: offer-presented ya se consume para la notificación #1 y trae
     * prospectId + applicationId + offeredTerm en un solo evento).
     */
    public void onOfferPresented(UUID applicationId, UUID prospectId, Integer offeredTerm) {
        applicationLinkRepository.save(ApplicationProspectLink.of(applicationId, prospectId, offeredTerm));
    }

    /**
     * Paso 3 — join final en la activación (contractId de CreditAccountActivated == applicationId).
     * También inicializa CreditAccountProgress con el totalInstallments ya conocido (offeredTerm).
     */
    public Optional<ContactInfo> onCreditAccountActivated(UUID creditAccountId, UUID obligorPartyId,
                                                            UUID applicationId, String productType) {
        Optional<ApplicationProspectLink> linkOpt = applicationLinkRepository.findById(applicationId);
        Integer totalInstallments = null;
        ContactInfo contact = ContactInfo.empty();

        if (linkOpt.isPresent()) {
            ApplicationProspectLink link = linkOpt.get();
            totalInstallments = link.getOfferedTerm();
            Optional<ProspectContactShadow> prospectContact = prospectContactRepository.findById(link.getProspectId());
            if (prospectContact.isPresent()) {
                ProspectContactShadow s = prospectContact.get();
                contact = new ContactInfo(s.getFirstName(), s.getPhone(), s.getEmail());
                partyContactRepository.save(
                        PartyContactDirectory.of(obligorPartyId, s.getFirstName(), s.getPhone(), s.getEmail()));
            } else {
                log.warn("ApplicationProspectLink found for applicationId={} but no ProspectContactShadow for "
                        + "prospectId={} — contact unresolved for partyId={}", applicationId, link.getProspectId(), obligorPartyId);
            }
        } else {
            log.warn("No ApplicationProspectLink for applicationId={} — contact unresolved for partyId={} "
                    + "(offer-presented may not have fired for this application)", applicationId, obligorPartyId);
        }

        progressRepository.save(CreditAccountProgress.init(creditAccountId, obligorPartyId, productType, totalInstallments));
        return contact.email() != null || contact.phone() != null ? Optional.of(contact) : Optional.empty();
    }

    public ContactInfo resolveProspectContact(UUID prospectId) {
        return prospectContactRepository.findById(prospectId)
                .map(s -> new ContactInfo(s.getFirstName(), s.getPhone(), s.getEmail()))
                .orElse(ContactInfo.empty());
    }

    public ContactInfo resolvePartyContact(UUID partyId) {
        return partyContactRepository.findById(partyId)
                .map(d -> new ContactInfo(d.getFirstName(), d.getPhone(), d.getEmail()))
                .orElse(ContactInfo.empty());
    }
}

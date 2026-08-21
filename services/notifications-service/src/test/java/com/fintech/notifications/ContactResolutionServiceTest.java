package com.fintech.notifications;

import com.fintech.notifications.application.port.out.*;
import com.fintech.notifications.application.service.ContactInfo;
import com.fintech.notifications.application.service.ContactResolutionService;
import com.fintech.notifications.domain.ApplicationProspectLink;
import com.fintech.notifications.domain.CreditAccountProgress;
import com.fintech.notifications.domain.ProspectContactShadow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class ContactResolutionServiceTest {

    @Mock ProspectContactShadowRepository prospectContactRepository;
    @Mock ApplicationProspectLinkRepository applicationLinkRepository;
    @Mock PartyContactDirectoryRepository partyContactRepository;
    @Mock CreditAccountProgressRepository progressRepository;

    ContactResolutionService service;

    @BeforeEach
    void setUp() {
        service = new ContactResolutionService(prospectContactRepository, applicationLinkRepository,
                partyContactRepository, progressRepository);
    }

    @Test
    void onProspectCreated_savesShadow() {
        UUID prospectId = UUID.randomUUID();

        service.onProspectCreated(prospectId, "Ana", "5511112222", "ana@example.com");

        ArgumentCaptor<ProspectContactShadow> captor = ArgumentCaptor.forClass(ProspectContactShadow.class);
        then(prospectContactRepository).should().save(captor.capture());
        assertThat(captor.getValue().getProspectId()).isEqualTo(prospectId);
        assertThat(captor.getValue().getPhone()).isEqualTo("5511112222");
    }

    @Test
    void onOfferPresented_savesLink() {
        UUID applicationId = UUID.randomUUID();
        UUID prospectId = UUID.randomUUID();

        service.onOfferPresented(applicationId, prospectId, 12);

        ArgumentCaptor<ApplicationProspectLink> captor = ArgumentCaptor.forClass(ApplicationProspectLink.class);
        then(applicationLinkRepository).should().save(captor.capture());
        assertThat(captor.getValue().getApplicationId()).isEqualTo(applicationId);
        assertThat(captor.getValue().getOfferedTerm()).isEqualTo(12);
    }

    @Test
    void onCreditAccountActivated_joinsLinkAndShadow_resolvesContactAndInitsProgress() {
        UUID applicationId = UUID.randomUUID();
        UUID prospectId = UUID.randomUUID();
        UUID creditAccountId = UUID.randomUUID();
        UUID obligorPartyId = UUID.randomUUID();

        given(applicationLinkRepository.findById(applicationId))
                .willReturn(Optional.of(ApplicationProspectLink.of(applicationId, prospectId, 12)));
        given(prospectContactRepository.findById(prospectId))
                .willReturn(Optional.of(ProspectContactShadow.of(prospectId, "Ana", "5511112222", "ana@example.com")));

        Optional<ContactInfo> contact = service.onCreditAccountActivated(
                creditAccountId, obligorPartyId, applicationId, "PERSONAL_LOAN");

        assertThat(contact).isPresent();
        assertThat(contact.get().phone()).isEqualTo("5511112222");
        then(partyContactRepository).should().save(any());

        ArgumentCaptor<CreditAccountProgress> progressCaptor = ArgumentCaptor.forClass(CreditAccountProgress.class);
        then(progressRepository).should().save(progressCaptor.capture());
        assertThat(progressCaptor.getValue().getTotalInstallments()).isEqualTo(12);
        assertThat(progressCaptor.getValue().getObligorPartyId()).isEqualTo(obligorPartyId);
    }

    @Test
    void onCreditAccountActivated_noApplicationLink_returnsEmptyContact_butStillInitsProgress() {
        UUID applicationId = UUID.randomUUID();
        UUID creditAccountId = UUID.randomUUID();
        UUID obligorPartyId = UUID.randomUUID();

        given(applicationLinkRepository.findById(applicationId)).willReturn(Optional.empty());

        Optional<ContactInfo> contact = service.onCreditAccountActivated(
                creditAccountId, obligorPartyId, applicationId, "SME_LOAN");

        assertThat(contact).isEmpty();
        then(partyContactRepository).should(never()).save(any());
        then(progressRepository).should().save(any()); // progreso se inicializa igual, sin totalInstallments
    }

    @Test
    void resolvePartyContact_missing_returnsEmptyContactInfo() {
        UUID partyId = UUID.randomUUID();
        given(partyContactRepository.findById(partyId)).willReturn(Optional.empty());

        ContactInfo contact = service.resolvePartyContact(partyId);

        assertThat(contact.phone()).isNull();
        assertThat(contact.email()).isNull();
    }
}

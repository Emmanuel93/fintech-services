package com.fintech.party.application.service;

import com.fintech.party.application.port.out.KycVerificationRepository;
import com.fintech.party.application.port.out.PartyEventPublisher;
import com.fintech.party.application.port.out.PartyRepository;
import com.fintech.party.domain.*;
import com.fintech.party.infrastructure.adapter.in.messaging.ProspectCreatedPayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class PartyServiceTest {

    @Mock PartyRepository partyRepository;
    @Mock KycVerificationRepository kycRepository;
    @Mock PartyEventPublisher eventPublisher;

    PartyService service;

    final UUID prospectId = UUID.randomUUID();
    final UUID partyId    = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new PartyService(partyRepository, kycRepository, eventPublisher);
        lenient().when(partyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(kycRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    // ── createFromProspect ────────────────────────────────────────────────────

    @Test
    void createFromProspect_newProspect_savesPartyAsProspectStatus() {
        given(partyRepository.existsByProspectId(prospectId)).willReturn(false);

        ProspectCreatedPayload payload = buildPayload(prospectId);
        Party result = service.createFromProspect(payload);

        ArgumentCaptor<Party> captor = ArgumentCaptor.forClass(Party.class);
        then(partyRepository).should().save(captor.capture());
        Party saved = captor.getValue();

        assertThat(saved.getProspectId()).isEqualTo(prospectId);
        assertThat(saved.getPartyType()).isEqualTo(PartyType.INDIVIDUAL);
        assertThat(saved.getStatus()).isEqualTo(PartyStatus.PROSPECT);
        assertThat(saved.getFirstName()).isEqualTo("Juan");
        assertThat(saved.getLastName1()).isEqualTo("García");
        assertThat(saved.getCurp()).isEqualTo("GARJ900101HDFXXX01");
        assertThat(saved.getEvaluationId()).isNull();
        assertThat(saved.getRiskLevel()).isNull();
        assertThat(result).isSameAs(saved);
    }

    @Test
    void createFromProspect_duplicateProspect_skipsCreationAndReturnsExisting() {
        Party existing = buildParty();
        given(partyRepository.existsByProspectId(prospectId)).willReturn(true);
        given(partyRepository.findByProspectId(prospectId)).willReturn(Optional.of(existing));

        Party result = service.createFromProspect(buildPayload(prospectId));

        then(partyRepository).should(never()).save(any());
        assertThat(result).isSameAs(existing);
    }

    // ── findById / findByProspectId ───────────────────────────────────────────

    @Test
    void findById_delegatesToRepository() {
        given(partyRepository.findById(partyId)).willReturn(Optional.empty());
        assertThat(service.findById(partyId)).isEmpty();
    }

    @Test
    void findByProspectId_delegatesToRepository() {
        given(partyRepository.findByProspectId(prospectId)).willReturn(Optional.empty());
        assertThat(service.findByProspectId(prospectId)).isEmpty();
    }

    // ── search / findByIds (modo consulta backoffice) ─────────────────────────

    @Test
    void search_delegatesToRepository() {
        Page<Party> page = new PageImpl<>(List.of(buildParty()));
        Pageable pageable = PageRequest.of(0, 10);
        given(partyRepository.search("garc", PartyType.INDIVIDUAL, PartyStatus.ACTIVE, null, pageable))
                .willReturn(page);

        assertThat(service.search("garc", PartyType.INDIVIDUAL, PartyStatus.ACTIVE, null, pageable))
                .isSameAs(page);
    }

    @Test
    void findByIds_delegatesToRepository() {
        List<UUID> ids = List.of(partyId);
        given(partyRepository.findByPartyIdIn(ids)).willReturn(List.of(buildParty()));

        assertThat(service.findByIds(ids)).hasSize(1);
        then(partyRepository).should().findByPartyIdIn(ids);
    }

    @Test
    void assignExecutive_setsExecutiveAndSaves() {
        Party party = buildParty();
        given(partyRepository.findById(partyId)).willReturn(Optional.of(party));
        UUID executiveId = UUID.randomUUID();

        Party result = service.assignExecutive(partyId, executiveId, "Ana Torres");

        assertThat(result.getAssignedExecutiveId()).isEqualTo(executiveId);
        assertThat(result.getAssignedExecutiveName()).isEqualTo("Ana Torres");
        then(partyRepository).should().save(party);
    }

    @Test
    void assignExecutive_throwsPartyNotFound_whenMissing() {
        given(partyRepository.findById(partyId)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.assignExecutive(partyId, UUID.randomUUID(), "Ana"))
                .isInstanceOf(PartyNotFoundException.class);
    }

    // ── addKycVerification ────────────────────────────────────────────────────

    @Test
    void addKycVerification_createsNewVerification_whenNoneExists() {
        Party party = buildParty();
        given(partyRepository.findById(partyId)).willReturn(Optional.of(party));
        given(kycRepository.findLatestByPartyIdAndDocumentType(partyId, "INE"))
                .willReturn(Optional.empty());

        KycVerification result = service.addKycVerification(
                partyId, "INE", "VERIFIED", "RENAPO-API",
                "doc-ref-001", LocalDate.of(2027, 1, 1), null);

        ArgumentCaptor<KycVerification> captor = ArgumentCaptor.forClass(KycVerification.class);
        then(kycRepository).should().save(captor.capture());
        assertThat(captor.getValue().getVerificationStatus()).isEqualTo("VERIFIED");
        assertThat(captor.getValue().getVerifiedBy()).isEqualTo("RENAPO-API");
    }

    @Test
    void addKycVerification_rejectsVerification_setsRejectionReason() {
        Party party = buildParty();
        given(partyRepository.findById(partyId)).willReturn(Optional.of(party));
        given(kycRepository.findLatestByPartyIdAndDocumentType(partyId, "INE"))
                .willReturn(Optional.empty());

        service.addKycVerification(partyId, "INE", "REJECTED", null, null, null, "Document expired");

        ArgumentCaptor<KycVerification> captor = ArgumentCaptor.forClass(KycVerification.class);
        then(kycRepository).should().save(captor.capture());
        assertThat(captor.getValue().getVerificationStatus()).isEqualTo("REJECTED");
        assertThat(captor.getValue().getRejectionReason()).isEqualTo("Document expired");
    }

    @Test
    void addKycVerification_throwsPartyNotFound_whenPartyMissing() {
        given(partyRepository.findById(partyId)).willReturn(Optional.empty());

        assertThatThrownBy(() ->
                service.addKycVerification(partyId, "INE", "VERIFIED", null, null, null, null))
                .isInstanceOf(PartyNotFoundException.class);

        then(kycRepository).should(never()).save(any());
    }

    // ── blacklistParty ────────────────────────────────────────────────────────

    @Test
    void blacklistParty_changesStatusToBlacklisted_andPublishesEvent() {
        Party party = buildParty();
        assertThat(party.getStatus()).isEqualTo(PartyStatus.PROSPECT);
        given(partyRepository.findById(partyId)).willReturn(Optional.of(party));

        Party result = service.blacklistParty(partyId, "AML match OFAC", "OFAC");

        assertThat(result.getStatus()).isEqualTo(PartyStatus.BLACKLISTED);
        then(partyRepository).should().save(party);
        then(eventPublisher).should().publishPartyBlacklisted(partyId, "AML match OFAC", "OFAC");
    }

    @Test
    void blacklistParty_throwsPartyNotFound_whenPartyMissing() {
        given(partyRepository.findById(partyId)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.blacklistParty(partyId, "reason", null))
                .isInstanceOf(PartyNotFoundException.class);

        then(eventPublisher).should(never()).publishPartyBlacklisted(any(), any(), any());
    }

    @Test
    void blacklistParty_throwsIllegalState_whenAlreadyBlacklisted() {
        Party party = buildParty();
        party.blacklist("first reason");
        given(partyRepository.findById(partyId)).willReturn(Optional.of(party));

        assertThatThrownBy(() -> service.blacklistParty(partyId, "second reason", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BLACKLISTED");
    }

    @Test
    void blacklistParty_persistsBlacklist_evenWhenKafkaFails() {
        Party party = buildParty();
        given(partyRepository.findById(partyId)).willReturn(Optional.of(party));
        willThrow(new RuntimeException("Kafka unavailable"))
                .given(eventPublisher).publishPartyBlacklisted(any(), any(), any());

        Party result = service.blacklistParty(partyId, "AML match", "CNBV");

        assertThat(result.getStatus()).isEqualTo(PartyStatus.BLACKLISTED);
        then(partyRepository).should().save(party);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private ProspectCreatedPayload buildPayload(UUID prospectId) {
        return new ProspectCreatedPayload(
                prospectId, "INDIVIDUAL", "Juan", "García", "López",
                "GARJ900101HDFXXX01", "GARJ900101XXX",
                LocalDate.of(1990, 1, 1), UUID.randomUUID().toString());
    }

    private Party buildParty() {
        return Party.create(partyId, prospectId,
                PartyType.INDIVIDUAL, "Juan", "García", "López",
                "GARJ900101HDFXXX01", "GARJ900101XXX",
                LocalDate.of(1990, 1, 1));
    }
}

package com.fintech.collections;

import com.fintech.collections.application.CollectionsProperties;
import com.fintech.collections.application.ProposeAgreementCommand;
import com.fintech.collections.application.port.out.AccountBalanceSnapshotRepository;
import com.fintech.collections.application.port.out.CollectionAgreementRepository;
import com.fintech.collections.application.port.out.CollectionCaseRepository;
import com.fintech.collections.application.port.out.CollectionsEventPublisher;
import com.fintech.collections.application.service.AgreementService;
import com.fintech.collections.application.service.BureauReportingService;
import com.fintech.collections.domain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class AgreementServiceTest {

    @Mock CollectionCaseRepository caseRepository;
    @Mock CollectionAgreementRepository agreementRepository;
    @Mock AccountBalanceSnapshotRepository snapshotRepository;
    @Mock BureauReportingService bureauReportingService;
    @Mock CollectionsEventPublisher eventPublisher;

    AgreementService service;
    CollectionsProperties properties;

    private final UUID creditAccountId = UUID.randomUUID();
    private final UUID obligorPartyId  = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        properties = new CollectionsProperties();
        service = new AgreementService(caseRepository, agreementRepository, snapshotRepository,
                bureauReportingService, eventPublisher, properties);
    }

    private CollectionCase managedCase() {
        CollectionCase c = CollectionCase.open(creditAccountId, obligorPartyId, "PERSONAL_LOAN",
                45, new BigDecimal("10000"), "AGENT_ASSIGNED_RESTRUCTURE_OFFER");
        c.escalate(45, new BigDecimal("10000"), "AGENT_ASSIGNED_RESTRUCTURE_OFFER"); // OPEN -> MANAGED
        return c;
    }

    @Test
    void propose_restructure_success() {
        CollectionCase c = managedCase();
        given(caseRepository.findById(c.getCaseId())).willReturn(Optional.of(c));
        given(agreementRepository.findActiveByCaseId(c.getCaseId())).willReturn(Optional.empty());
        given(snapshotRepository.findById(creditAccountId)).willReturn(Optional.empty());
        given(agreementRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        var terms = new RestructureTerms(new BigDecimal("0.25"), 12, "FRENCH");
        var cmd = new ProposeAgreementCommand(c.getCaseId(), AgreementType.RESTRUCTURE, null, terms);

        CollectionAgreement result = service.propose(cmd);

        assertThat(result.getType()).isEqualTo(AgreementType.RESTRUCTURE);
        assertThat(result.getStatus()).isEqualTo(AgreementStatus.PROPOSED);
        then(eventPublisher).should().publishCollectionAgreementProposed(result);
    }

    @Test
    void propose_restructure_throws_whenTermExceedsMax() {
        CollectionCase c = managedCase();
        given(caseRepository.findById(c.getCaseId())).willReturn(Optional.of(c));
        given(agreementRepository.findActiveByCaseId(c.getCaseId())).willReturn(Optional.empty());

        var terms = new RestructureTerms(new BigDecimal("0.25"), 24, "FRENCH"); // > 12 months max
        var cmd = new ProposeAgreementCommand(c.getCaseId(), AgreementType.RESTRUCTURE, null, terms);

        assertThatThrownBy(() -> service.propose(cmd)).isInstanceOf(InvalidAgreementStateException.class);
    }

    @Test
    void propose_quitaParcial_throws_whenForgivenessExceedsLimit() {
        CollectionCase c = managedCase();
        given(caseRepository.findById(c.getCaseId())).willReturn(Optional.of(c));
        given(agreementRepository.findActiveByCaseId(c.getCaseId())).willReturn(Optional.empty());
        given(snapshotRepository.findById(creditAccountId)).willReturn(Optional.empty());

        // maxForgivenessPct defaults to 0.30 of originalDebt(10000) = 3000
        var cmd = new ProposeAgreementCommand(c.getCaseId(), AgreementType.QUITA_PARCIAL, new BigDecimal("5000"), null);

        assertThatThrownBy(() -> service.propose(cmd)).isInstanceOf(ForgivenessLimitExceededException.class);
    }

    @Test
    void propose_throws_whenCaseNotManagedOrLegal() {
        CollectionCase c = CollectionCase.open(creditAccountId, obligorPartyId, "PERSONAL_LOAN",
                10, new BigDecimal("1000"), "AUTO_NOTIFY"); // still OPEN, B1_30
        given(caseRepository.findById(c.getCaseId())).willReturn(Optional.of(c));

        var cmd = new ProposeAgreementCommand(c.getCaseId(), AgreementType.QUITA_PARCIAL, new BigDecimal("100"), null);

        assertThatThrownBy(() -> service.propose(cmd)).isInstanceOf(InvalidCaseStateException.class);
    }

    @Test
    void propose_throws_whenActiveAgreementAlreadyExists() {
        CollectionCase c = managedCase();
        CollectionAgreement existing = CollectionAgreement.proposeRestructure(c.getCaseId(), creditAccountId,
                obligorPartyId, new BigDecimal("10000"), new RestructureTerms(new BigDecimal("0.2"), 6, "FRENCH"));
        given(caseRepository.findById(c.getCaseId())).willReturn(Optional.of(c));
        given(agreementRepository.findActiveByCaseId(c.getCaseId())).willReturn(Optional.of(existing));

        var cmd = new ProposeAgreementCommand(c.getCaseId(), AgreementType.QUITA_PARCIAL, new BigDecimal("100"), null);

        assertThatThrownBy(() -> service.propose(cmd)).isInstanceOf(InvalidCaseStateException.class);
    }

    @Test
    void authorize_executesAgreement_andCreatesBureauReport_forQuitaParcial() {
        CollectionAgreement agreement = CollectionAgreement.proposeQuitaParcial(UUID.randomUUID(), creditAccountId,
                obligorPartyId, new BigDecimal("10000"), new BigDecimal("2000"), new BigDecimal("0.30"));
        agreement.accept();
        given(agreementRepository.findById(agreement.getAgreementId())).willReturn(Optional.of(agreement));
        given(agreementRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        CollectionAgreement result = service.authorize(agreement.getAgreementId(), "ops-1", "AUTH-1");

        assertThat(result.getStatus()).isEqualTo(AgreementStatus.EXECUTED);
        then(eventPublisher).should().publishCollectionAgreementExecuted(agreement);
        then(bureauReportingService).should().createPendingReport(creditAccountId, obligorPartyId,
                BureauEventType.QUITA_PARCIAL, agreement.getAgreementId(), agreement.getForgivenAmount());
    }

    @Test
    void authorize_executesAgreement_withoutBureauReport_forRestructure() {
        CollectionAgreement agreement = CollectionAgreement.proposeRestructure(UUID.randomUUID(), creditAccountId,
                obligorPartyId, new BigDecimal("10000"), new RestructureTerms(new BigDecimal("0.2"), 6, "FRENCH"));
        agreement.accept();
        given(agreementRepository.findById(agreement.getAgreementId())).willReturn(Optional.of(agreement));
        given(agreementRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        service.authorize(agreement.getAgreementId(), "ops-1", "AUTH-1");

        then(bureauReportingService).shouldHaveNoInteractions();
    }

    @Test
    void accept_delegatesToDomain() {
        CollectionAgreement agreement = CollectionAgreement.proposeRestructure(UUID.randomUUID(), creditAccountId,
                obligorPartyId, new BigDecimal("10000"), new RestructureTerms(new BigDecimal("0.2"), 6, "FRENCH"));
        given(agreementRepository.findById(agreement.getAgreementId())).willReturn(Optional.of(agreement));
        given(agreementRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        service.accept(agreement.getAgreementId());

        assertThat(agreement.getStatus()).isEqualTo(AgreementStatus.ACCEPTED);
    }

    @Test
    void reject_delegatesToDomain() {
        CollectionAgreement agreement = CollectionAgreement.proposeRestructure(UUID.randomUUID(), creditAccountId,
                obligorPartyId, new BigDecimal("10000"), new RestructureTerms(new BigDecimal("0.2"), 6, "FRENCH"));
        given(agreementRepository.findById(agreement.getAgreementId())).willReturn(Optional.of(agreement));
        given(agreementRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        service.reject(agreement.getAgreementId());

        assertThat(agreement.getStatus()).isEqualTo(AgreementStatus.REJECTED);
    }
}

package com.fintech.origination;

import com.fintech.origination.application.GenerateContractCommand;
import com.fintech.origination.application.SignContractCommand;
import com.fintech.origination.application.port.out.ClabeValidator;
import com.fintech.origination.application.port.out.ContractEventPublisher;
import com.fintech.origination.application.port.out.CreditApplicationRepository;
import com.fintech.origination.application.port.out.ProspectRepository;
import com.fintech.origination.application.port.out.SignatureValidator;
import com.fintech.origination.application.service.ContractService;
import com.fintech.origination.domain.ApplicationStatus;
import com.fintech.origination.domain.CreditApplication;
import com.fintech.origination.domain.CreditOffer;
import com.fintech.origination.domain.ProductType;
import com.fintech.origination.domain.ProspectType;
import com.fintech.origination.domain.event.ContractSignedEvent;
import com.fintech.origination.domain.event.CreditProductCreationRequestedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class ContractServiceTest {

    @Mock CreditApplicationRepository applicationRepository;
    @Mock ProspectRepository prospectRepository;
    @Mock SignatureValidator signatureValidator;
    @Mock ClabeValidator clabeValidator;
    @Mock ContractEventPublisher eventPublisher;

    ContractService service;
    final UUID appId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new ContractService(applicationRepository, prospectRepository,
                signatureValidator, clabeValidator, eventPublisher);
    }

    private CreditApplication appWithAcceptedOffer() {
        CreditApplication app = CreditApplication.start(
                UUID.randomUUID(), ProspectType.INDIVIDUAL, ProductType.PERSONAL_LOAN,
                new BigDecimal("50000"), 12);
        app.approve("BAJO", "AUTO_APPROVED");
        CreditOffer offer = CreditOffer.create(
                "PL-001", 1, "INSTALLMENT", "FRENCH",
                new BigDecimal("50000"), null, 12,
                new BigDecimal("24.0"), new BigDecimal("36.0"), new BigDecimal("3.0"),
                new BigDecimal("27.50"),
                Instant.now().plus(72, ChronoUnit.HOURS));
        app.presentOffer(offer);
        app.acceptOffer();
        return app;
    }

    @Test
    void generate_transitions_to_pending_signature() {
        CreditApplication app = appWithAcceptedOffer();
        given(applicationRepository.findById(appId)).willReturn(Optional.of(app));
        given(applicationRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        CreditApplication result = service.generate(new GenerateContractCommand(appId, "ELECTRONIC"));

        assertThat(result.getStatus()).isEqualTo(ApplicationStatus.PENDING_SIGNATURE);
        assertThat(result.getContract()).isNotNull();
        assertThat(result.getContract().getContractNumber()).startsWith("CTR-");
        assertThat(result.getContract().getSignatureMethod()).isEqualTo("ELECTRONIC");
    }

    @Test
    void sign_transitions_to_contract_signed_and_publishes_both_events() {
        CreditApplication app = appWithAcceptedOffer();
        app.generateContract(com.fintech.origination.domain.Contract.generate("CTR-202606-ABCD1234", "ELECTRONIC"));
        given(applicationRepository.findById(appId)).willReturn(Optional.of(app));
        given(applicationRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(clabeValidator.isValid("032180000118359719")).willReturn(true);
        given(signatureValidator.isValid(any(), any())).willReturn(true);

        CreditApplication result = service.sign(new SignContractCommand(
                appId, "032180000118359719", "SIG-PROOF", null));

        assertThat(result.getStatus()).isEqualTo(ApplicationStatus.CONTRACT_SIGNED);
        assertThat(result.getContract().getClabeAccount()).isEqualTo("032180000118359719");
        assertThat(result.getContract().getSignedAt()).isNotNull();

        then(eventPublisher).should().publishContractSigned(any(ContractSignedEvent.class));
        then(eventPublisher).should().publishCreditProductCreationRequested(any(CreditProductCreationRequestedEvent.class));
    }

    @Test
    void sign_throws_when_clabe_invalid() {
        CreditApplication app = appWithAcceptedOffer();
        app.generateContract(com.fintech.origination.domain.Contract.generate("CTR-202606-ABCD1234", "ELECTRONIC"));
        given(applicationRepository.findById(appId)).willReturn(Optional.of(app));
        given(clabeValidator.isValid("000")).willReturn(false);

        assertThatThrownBy(() -> service.sign(new SignContractCommand(appId, "000", "SIG", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CM-06");
    }

    @Test
    void snapshot_contains_offer_terms() {
        CreditApplication app = appWithAcceptedOffer();
        app.generateContract(com.fintech.origination.domain.Contract.generate("CTR-202606-ABCD1234", "ELECTRONIC"));
        given(applicationRepository.findById(appId)).willReturn(Optional.of(app));
        given(applicationRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(clabeValidator.isValid("032180000118359719")).willReturn(true);
        given(signatureValidator.isValid(any(), any())).willReturn(true);

        ArgumentCaptor<CreditProductCreationRequestedEvent> captor =
                ArgumentCaptor.forClass(CreditProductCreationRequestedEvent.class);

        service.sign(new SignContractCommand(appId, "032180000118359719", "SIG", null));
        then(eventPublisher).should().publishCreditProductCreationRequested(captor.capture());

        CreditProductCreationRequestedEvent snapshot = captor.getValue();
        assertThat(snapshot.getProductCode()).isEqualTo("PL-001");
        assertThat(snapshot.getNominalRate()).isEqualByComparingTo("24.0");
        assertThat(snapshot.getAssignedTerm()).isEqualTo(12);
        assertThat(snapshot.getClabeAccount()).isEqualTo("032180000118359719");
        assertThat(snapshot.getProductBehavior()).isEqualTo("INSTALLMENT");
    }

    @Test
    void apply_disbursement_marks_disbursed_when_contract_signed() {
        CreditApplication app = appWithAcceptedOffer();
        app.generateContract(com.fintech.origination.domain.Contract.generate("CTR-202606-ABCD1234", "ELECTRONIC"));
        app.signContract("032180000118359719", "DOC-REF");
        given(applicationRepository.findById(appId)).willReturn(Optional.of(app));
        given(applicationRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        service.apply(appId);

        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.DISBURSED);
    }
}

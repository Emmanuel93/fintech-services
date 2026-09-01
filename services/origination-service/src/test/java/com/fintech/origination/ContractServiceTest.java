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
import com.fintech.origination.domain.Contract;
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

    /** Una solicitud lista para firmar: con oferta aceptada y contrato ya generado. */
    private CreditApplication applicationPendingSignature() {
        CreditApplication app = appWithAcceptedOffer();
        app.generateContract(Contract.generate("CTR-BNPL-1", "ELECTRONIC"));
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
                appId, "032180000118359719", "SIG-PROOF", null, null));

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

        assertThatThrownBy(() -> service.sign(new SignContractCommand(appId, "000", "SIG", null, null)))
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

        service.sign(new SignContractCommand(appId, "032180000118359719", "SIG", null, null));
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
        app.signContract("032180000118359719", "DOC-REF", null);
        given(applicationRepository.findById(appId)).willReturn(Optional.of(app));
        given(applicationRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        service.apply(appId);

        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.DISBURSED);
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("Lo que el cliente pide de BNPL al firmar viaja en el hecho, no se queda en la firma")
    void el_bnpl_pedido_al_firmar_viaja_en_el_hecho() {
        // BNPL es una decisión del alta. Mientras no hubo dónde guardarla, cartera no podía
        // distinguir «lo pidió» de «no lo pidió» y aplicaba el tope del producto a toda cuenta
        // suya: ningún préstamo personal empezaba a pagar cuando debía.
        CreditApplication app = applicationPendingSignature();
        given(applicationRepository.findById(appId)).willReturn(java.util.Optional.of(app));
        given(clabeValidator.isValid(any())).willReturn(true);
        given(signatureValidator.isValid(any(), any())).willReturn(true);
        given(applicationRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        ArgumentCaptor<CreditProductCreationRequestedEvent> captor =
                ArgumentCaptor.forClass(CreditProductCreationRequestedEvent.class);

        service.sign(new SignContractCommand(appId, "032180000118359719", "SIG", null, 30));

        then(eventPublisher).should().publishCreditProductCreationRequested(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getBnplDeferralDays()).isEqualTo(30);
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("Sin pedirlo, el hecho lleva nulo — que es lo que hace que NO se aplique")
    void sin_pedirlo_el_hecho_lleva_nulo() {
        CreditApplication app = applicationPendingSignature();
        given(applicationRepository.findById(appId)).willReturn(java.util.Optional.of(app));
        given(clabeValidator.isValid(any())).willReturn(true);
        given(signatureValidator.isValid(any(), any())).willReturn(true);
        given(applicationRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        ArgumentCaptor<CreditProductCreationRequestedEvent> captor =
                ArgumentCaptor.forClass(CreditProductCreationRequestedEvent.class);

        service.sign(new SignContractCommand(appId, "032180000118359719", "SIG", null, null));

        then(eventPublisher).should().publishCreditProductCreationRequested(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getBnplDeferralDays()).isNull();
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("Pedir cero es no pedir: no se guarda como decisión")
    void pedir_cero_es_no_pedir() {
        CreditApplication app = applicationPendingSignature();
        given(applicationRepository.findById(appId)).willReturn(java.util.Optional.of(app));
        given(clabeValidator.isValid(any())).willReturn(true);
        given(signatureValidator.isValid(any(), any())).willReturn(true);
        given(applicationRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        service.sign(new SignContractCommand(appId, "032180000118359719", "SIG", null, 0));

        org.assertj.core.api.Assertions.assertThat(app.getContract().getBnplDeferralDays()).isNull();
    }
}

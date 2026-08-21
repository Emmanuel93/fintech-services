package com.fintech.origination;

import com.fintech.origination.application.CreditProductDefinition;
import com.fintech.origination.application.PresentOfferCommand;
import com.fintech.origination.application.port.out.CreditApplicationRepository;
import com.fintech.origination.application.port.out.OfferEventPublisher;
import com.fintech.origination.application.port.out.ProductCatalogReader;
import com.fintech.origination.application.service.OfferService;
import com.fintech.origination.domain.ApplicationStatus;
import com.fintech.origination.domain.CreditApplication;
import com.fintech.origination.domain.CreditApplicationNotFoundException;
import com.fintech.origination.domain.ProductType;
import com.fintech.origination.domain.ProspectType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class OfferServiceTest {

    @Mock CreditApplicationRepository applicationRepository;
    @Mock ProductCatalogReader catalogReader;
    @Mock OfferEventPublisher eventPublisher;

    OfferService service;

    final UUID appId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new OfferService(applicationRepository, catalogReader, eventPublisher);
    }

    private CreditApplication approvedApp() {
        CreditApplication app = CreditApplication.start(
                UUID.randomUUID(), ProspectType.INDIVIDUAL, ProductType.PERSONAL_LOAN,
                new BigDecimal("50000"), 12);
        app.approve("BAJO", "AUTO_APPROVED");
        return app;
    }

    private CreditProductDefinition personalLoanProduct() {
        return new CreditProductDefinition(
                UUID.randomUUID(), "PL-001", 1, "PERSONAL_LOAN", "INSTALLMENT",
                "Préstamo Personal", "ACTIVE", "MXN",
                new BigDecimal("24.0"), new BigDecimal("36.0"),
                6, 36, 12,
                new BigDecimal("5000"), new BigDecimal("100000"), null, null, null,
                "FRENCH", "MONTHLY", 150, "AUTOMATIC",
                new BigDecimal("3.0"), new BigDecimal("2.0"));
    }

    @Test
    void present_transitions_to_offer_presented() {
        CreditApplication app = approvedApp();
        given(applicationRepository.findById(appId)).willReturn(Optional.of(app));
        given(catalogReader.findByCode("PL-001")).willReturn(Optional.of(personalLoanProduct()));
        given(applicationRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        CreditApplication result = service.present(
                new PresentOfferCommand(appId, "PL-001", new BigDecimal("40000"), 12));

        assertThat(result.getStatus()).isEqualTo(ApplicationStatus.OFFER_PRESENTED);
        assertThat(result.getOffer()).isNotNull();
        assertThat(result.getOffer().getNominalRate()).isEqualByComparingTo("24.0");
        assertThat(result.getOffer().getCat()).isGreaterThan(new BigDecimal("24.0"));
        then(eventPublisher).should().publish(any());
    }

    @Test
    void present_uses_requested_amount_when_offered_amount_null() {
        CreditApplication app = approvedApp(); // requestedAmount = 50000
        given(applicationRepository.findById(appId)).willReturn(Optional.of(app));
        given(catalogReader.findByCode("PL-001")).willReturn(Optional.of(personalLoanProduct()));
        given(applicationRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        CreditApplication result = service.present(new PresentOfferCommand(appId, "PL-001", null, null));

        assertThat(result.getOffer().getOfferedAmount()).isEqualByComparingTo("50000");
    }

    @Test
    void present_throws_when_offered_amount_exceeds_max() {
        CreditApplication app = approvedApp();
        given(applicationRepository.findById(appId)).willReturn(Optional.of(app));
        given(catalogReader.findByCode("PL-001")).willReturn(Optional.of(personalLoanProduct()));

        assertThatThrownBy(() -> service.present(
                new PresentOfferCommand(appId, "PL-001", new BigDecimal("200000"), 12)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("OM-02");
    }

    @Test
    void present_throws_when_product_type_mismatch() {
        CreditApplication app = CreditApplication.start(
                UUID.randomUUID(), ProspectType.INDIVIDUAL, ProductType.REVOLVING_LINE,
                null, null);
        app.approve("BAJO", "AUTO_APPROVED");
        given(applicationRepository.findById(appId)).willReturn(Optional.of(app));
        given(catalogReader.findByCode("PL-001")).willReturn(Optional.of(personalLoanProduct()));

        assertThatThrownBy(() -> service.present(
                new PresentOfferCommand(appId, "PL-001", null, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void present_throws_when_application_not_found() {
        given(applicationRepository.findById(appId)).willReturn(Optional.empty());
        assertThatThrownBy(() -> service.present(new PresentOfferCommand(appId, "PL-001", null, null)))
                .isInstanceOf(CreditApplicationNotFoundException.class);
    }

    @Test
    void accept_transitions_to_offer_accepted() {
        CreditApplication app = approvedApp();
        app.presentOffer(com.fintech.origination.domain.CreditOffer.create(
                "PL-001", 1, "INSTALLMENT", "FRENCH",
                new BigDecimal("50000"), null, 12,
                new BigDecimal("24.0"), new BigDecimal("36.0"), new BigDecimal("3.0"),
                new BigDecimal("27.50"),
                java.time.Instant.now().plus(72, java.time.temporal.ChronoUnit.HOURS)));
        given(applicationRepository.findById(appId)).willReturn(Optional.of(app));
        given(applicationRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        CreditApplication result = service.accept(appId);

        assertThat(result.getStatus()).isEqualTo(ApplicationStatus.OFFER_ACCEPTED);
        assertThat(result.getOffer().getAcceptedAt()).isNotNull();
    }

    @Test
    void reject_transitions_to_offer_rejected() {
        CreditApplication app = approvedApp();
        app.presentOffer(com.fintech.origination.domain.CreditOffer.create(
                "PL-001", 1, "INSTALLMENT", "FRENCH",
                new BigDecimal("50000"), null, 12,
                new BigDecimal("24.0"), new BigDecimal("36.0"), new BigDecimal("3.0"),
                new BigDecimal("27.50"),
                java.time.Instant.now().plus(72, java.time.temporal.ChronoUnit.HOURS)));
        given(applicationRepository.findById(appId)).willReturn(Optional.of(app));
        given(applicationRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        CreditApplication result = service.reject(appId);

        assertThat(result.getStatus()).isEqualTo(ApplicationStatus.OFFER_REJECTED);
    }
}

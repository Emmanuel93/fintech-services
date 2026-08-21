package com.fintech.origination;

import com.fintech.origination.application.port.out.CreditApplicationRepository;
import com.fintech.origination.domain.ApplicationStatus;
import com.fintech.origination.domain.CreditApplication;
import com.fintech.origination.domain.CreditOffer;
import com.fintech.origination.domain.ProductType;
import com.fintech.origination.domain.ProspectType;
import com.fintech.origination.infrastructure.job.OfferExpirationJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
class OfferExpirationJobTest {

    @Mock CreditApplicationRepository applicationRepository;

    OfferExpirationJob job;

    @BeforeEach
    void setUp() {
        job = new OfferExpirationJob(applicationRepository);
    }

    private CreditApplication appWithExpiredOffer() {
        CreditApplication app = CreditApplication.start(UUID.randomUUID(), ProspectType.INDIVIDUAL,
                ProductType.PERSONAL_LOAN, new BigDecimal("50000"), 12);
        app.approve("BAJO", "AUTO_APPROVED");
        app.presentOffer(CreditOffer.create(
                "PRESTAMO_PERSONAL_001", 1, "INSTALLMENT", "FRENCH",
                new BigDecimal("50000"), null, 12,
                new BigDecimal("0.18"), new BigDecimal("0.05"), new BigDecimal("0.01"),
                new BigDecimal("25.5"),
                Instant.now().minusSeconds(3600))); // expired 1 hour ago
        return app;
    }

    @Test
    void expireStaleOffers_noExpired_doesNotSave() {
        given(applicationRepository.findExpiredOffers(any(Instant.class))).willReturn(List.of());

        job.expireStaleOffers();

        then(applicationRepository).should(never()).save(any());
    }

    @Test
    void expireStaleOffers_oneExpired_savesExpiredStatus() {
        CreditApplication app = appWithExpiredOffer();
        given(applicationRepository.findExpiredOffers(any(Instant.class))).willReturn(List.of(app));

        job.expireStaleOffers();

        ArgumentCaptor<CreditApplication> captor = ArgumentCaptor.forClass(CreditApplication.class);
        then(applicationRepository).should().save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(ApplicationStatus.OFFER_EXPIRED);
    }

    @Test
    void expireStaleOffers_multipleExpired_savesEach() {
        CreditApplication app1 = appWithExpiredOffer();
        CreditApplication app2 = appWithExpiredOffer();
        given(applicationRepository.findExpiredOffers(any(Instant.class))).willReturn(List.of(app1, app2));

        job.expireStaleOffers();

        then(applicationRepository).should(times(2)).save(any());
    }

    @Test
    void expireSingle_transitionsToOfferExpired() {
        CreditApplication app = appWithExpiredOffer();
        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.OFFER_PRESENTED);

        job.expireSingle(app);

        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.OFFER_EXPIRED);
        then(applicationRepository).should().save(app);
    }

    @Test
    void expireSingle_exceptionDuringExpiry_doesNotPropagateAllowingOtherProcessing() {
        CreditApplication invalidApp = CreditApplication.start(UUID.randomUUID(), ProspectType.INDIVIDUAL,
                ProductType.PERSONAL_LOAN, new BigDecimal("50000"), 12);
        // PENDING_SCORING — expireOffer() will throw IllegalStateException

        // should not throw; catches and logs the error
        job.expireSingle(invalidApp);

        then(applicationRepository).should(never()).save(any());
    }
}

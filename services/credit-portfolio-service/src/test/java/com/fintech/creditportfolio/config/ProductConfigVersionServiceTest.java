package com.fintech.creditportfolio.config;

import com.fintech.creditportfolio.application.port.out.ProductConfigVersionRepository;
import com.fintech.creditportfolio.application.service.ProductConfigVersionService;
import com.fintech.creditportfolio.domain.config.Capabilities;
import com.fintech.creditportfolio.domain.config.ProductConfigVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class ProductConfigVersionServiceTest {

    @Mock ProductConfigVersionRepository repository;
    ProductConfigVersionService service;

    private static final Capabilities INSTALLMENT_CAPS = new Capabilities(
            true, false, false, "SELF_USE", false, false, false, false, false);

    @BeforeEach
    void setUp() {
        service = new ProductConfigVersionService(repository);
        lenient().when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void upsertActivated_newVersion_stored() {
        given(repository.findByCodeAndVersion("PL-STD", 1)).willReturn(Optional.empty());

        service.upsertActivated("PL-STD", 1, "PERSONAL_LOAN", "INSTALLMENT", "B2C",
                INSTALLMENT_CAPS, "FRENCH", "MONTHLY", 1000,
                new BigDecimal("0.32"), new BigDecimal("0.55"), new BigDecimal("0.01"));

        ArgumentCaptor<ProductConfigVersion> captor = ArgumentCaptor.forClass(ProductConfigVersion.class);
        then(repository).should().save(captor.capture());
        ProductConfigVersion saved = captor.getValue();
        assertThat(saved.getProductCode()).isEqualTo("PL-STD");
        assertThat(saved.getProductVersion()).isEqualTo(1);
        assertThat(saved.getStatus()).isEqualTo("ACTIVE");
        assertThat(saved.isDegraded()).isFalse();
        assertThat(saved.getCapabilities().hasAmortizationSchedule()).isTrue();
        assertThat(saved.getAmountStep()).isEqualTo(1000);
    }

    @Test
    void upsertActivated_existingVersion_overwritesAndClearsDegraded() {
        ProductConfigVersion degraded = ProductConfigVersion.of(
                "PL-STD", 1, "PERSONAL_LOAN", "INSTALLMENT", "B2C",
                Capabilities.degradedFor("INSTALLMENT", null),
                "FRENCH", null, null,
                new BigDecimal("0.32"), new BigDecimal("0.55"), null,
                "ACTIVE", true);    // degraded fallback materialized earlier
        given(repository.findByCodeAndVersion("PL-STD", 1)).willReturn(Optional.of(degraded));

        service.upsertActivated("PL-STD", 1, "PERSONAL_LOAN", "INSTALLMENT", "B2C",
                INSTALLMENT_CAPS, "FRENCH", "MONTHLY", 1000,
                new BigDecimal("0.32"), new BigDecimal("0.55"), new BigDecimal("0.01"));

        assertThat(degraded.isDegraded()).isFalse();
        assertThat(degraded.getPaymentFrequency()).isEqualTo("MONTHLY");
        assertThat(degraded.getAmountStep()).isEqualTo(1000);
        then(repository).should().save(degraded);
    }

    @Test
    void upsertActivated_nullCapabilities_usesDegradedDefault() {
        given(repository.findByCodeAndVersion("RL-STD", 1)).willReturn(Optional.empty());

        service.upsertActivated("RL-STD", 1, "REVOLVING_LINE", "REVOLVING", "B2C",
                null, null, "MONTHLY", 1000,
                new BigDecimal("0.38"), new BigDecimal("0.60"), null);

        ArgumentCaptor<ProductConfigVersion> captor = ArgumentCaptor.forClass(ProductConfigVersion.class);
        then(repository).should().save(captor.capture());
        Capabilities caps = captor.getValue().getCapabilities();
        assertThat(caps.hasCreditLimit()).isTrue();          // revolving default
        assertThat(caps.hasAmortizationSchedule()).isFalse();
    }

    @Test
    void retire_existingVersion_marksRetired() {
        ProductConfigVersion active = ProductConfigVersion.of(
                "PL-STD", 1, "PERSONAL_LOAN", "INSTALLMENT", "B2C", INSTALLMENT_CAPS,
                "FRENCH", "MONTHLY", 1000,
                new BigDecimal("0.32"), new BigDecimal("0.55"), new BigDecimal("0.01"),
                "ACTIVE", false);
        given(repository.findByCodeAndVersion("PL-STD", 1)).willReturn(Optional.of(active));

        service.retire("PL-STD", 1);

        assertThat(active.getStatus()).isEqualTo("RETIRED");
        then(repository).should().save(active);
    }

    @Test
    void retire_unknownVersion_noSave() {
        given(repository.findByCodeAndVersion("PL-STD", 9)).willReturn(Optional.empty());
        service.retire("PL-STD", 9);
        then(repository).should(never()).save(any());
    }
}

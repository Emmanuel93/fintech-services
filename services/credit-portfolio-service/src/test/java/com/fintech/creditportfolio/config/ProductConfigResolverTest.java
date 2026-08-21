package com.fintech.creditportfolio.config;

import com.fintech.creditportfolio.application.CreateCreditAccountCommand;
import com.fintech.creditportfolio.application.port.out.ProductConfigVersionRepository;
import com.fintech.creditportfolio.application.service.ProductConfigResolver;
import com.fintech.creditportfolio.application.service.ProductConfigVersionService;
import com.fintech.creditportfolio.domain.config.Capabilities;
import com.fintech.creditportfolio.domain.config.ProductConfigVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class ProductConfigResolverTest {

    @Mock ProductConfigVersionRepository repository;
    @Mock ProductConfigVersionService configService;
    ProductConfigResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new ProductConfigResolver(repository, configService);
    }

    private CreateCreditAccountCommand cmd(Integer version) {
        return new CreateCreditAccountCommand(
                java.util.UUID.randomUUID(), "CTR-1", java.util.UUID.randomUUID(),
                "PL-001", version, "PERSONAL_LOAN", "INSTALLMENT",
                new BigDecimal("50000"), null, 12,
                new BigDecimal("0.24"), new BigDecimal("0.36"),
                "FRENCH", new BigDecimal("0.03"), "032180000118359719", "BAJO", null, null, null, null, null);
    }

    private ProductConfigVersion version(int v, boolean degraded) {
        return ProductConfigVersion.of("PL-001", v, "PERSONAL_LOAN", "INSTALLMENT", "B2C",
                new Capabilities(true, false, false, "SELF_USE", false, false, false, false, false),
                "FRENCH", "MONTHLY", 1000,
                new BigDecimal("0.24"), new BigDecimal("0.36"), new BigDecimal("0.03"),
                "ACTIVE", degraded);
    }

    @Test
    void exactPin_present_returnsPinnedVersion() {
        given(repository.findByCodeAndVersion("PL-001", 2)).willReturn(Optional.of(version(2, false)));

        ProductConfigVersion result = resolver.resolveForActivation(cmd(2));

        assertThat(result.getProductVersion()).isEqualTo(2);
        assertThat(result.isDegraded()).isFalse();
        then(configService).should(never()).materializeDegraded(any(), anyInt(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void exactPin_missing_materializesDegradedAtThatVersion() {
        given(repository.findByCodeAndVersion("PL-001", 5)).willReturn(Optional.empty());
        given(configService.materializeDegraded(eq("PL-001"), eq(5), any(), any(), any(), any(), any(), any(), any()))
                .willReturn(version(5, true));

        ProductConfigVersion result = resolver.resolveForActivation(cmd(5));

        assertThat(result.getProductVersion()).isEqualTo(5);
        assertThat(result.isDegraded()).isTrue();
        then(configService).should().materializeDegraded(eq("PL-001"), eq(5), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void noPin_usesLatestActive() {
        given(repository.findLatestActiveByCode("PL-001")).willReturn(Optional.of(version(3, false)));

        ProductConfigVersion result = resolver.resolveForActivation(cmd(null));

        assertThat(result.getProductVersion()).isEqualTo(3);
    }

    @Test
    void noPin_noActive_materializesDegradedV1() {
        given(repository.findLatestActiveByCode("PL-001")).willReturn(Optional.empty());
        given(configService.materializeDegraded(eq("PL-001"), eq(1), any(), any(), any(), any(), any(), any(), any()))
                .willReturn(version(1, true));

        ProductConfigVersion result = resolver.resolveForActivation(cmd(null));

        assertThat(result.getProductVersion()).isEqualTo(1);
        assertThat(result.isDegraded()).isTrue();
    }
}

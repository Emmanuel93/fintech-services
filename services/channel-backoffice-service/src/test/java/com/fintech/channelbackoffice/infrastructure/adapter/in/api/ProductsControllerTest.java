package com.fintech.channelbackoffice.infrastructure.adapter.in.api;

import com.fintech.channelbackoffice.infrastructure.adapter.in.api.ProductsController.CreateProductRequest;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.CreditProductClient;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.ScoringClient;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.CreditProductClient.CreditProductResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductsControllerTest {

    private final CreditProductClient client = mock(CreditProductClient.class);
    private final ProductsController controller = new ProductsController(client, mock(ScoringClient.class));

    @Test
    void list_mapsToProductDefinitionShape() {
        when(client.findAll()).thenReturn(List.of(product("ACTIVE")));

        List<Map<String, Object>> out = controller.list();

        assertThat(out).hasSize(1);
        assertThat(out.get(0)).containsKeys("id", "productCode", "status", "activeInApp",
                "nominalRateAnnual", "targetAudience");
        assertThat(out.get(0)).containsEntry("activeInApp", true);
    }

    @Test
    @SuppressWarnings("unchecked")
    void create_fillsDomainDefaults_forInstallmentB2C() {
        when(client.create(any())).thenReturn(product("DRAFT"));

        controller.create(new CreateProductRequest(
                "PL-NEW", "PERSONAL_LOAN", "INSTALLMENT", "Nuevo", "desc", "B2C",
                new BigDecimal("0.32"), new BigDecimal("0.55"), 3, 36, 12,
                new BigDecimal("5000"), new BigDecimal("150000"), null, null, null,
                "FRENCH", 200, "AUTOMATIC", new BigDecimal("0.01"), new BigDecimal("0.02")));

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(client).create(captor.capture());
        Map<String, Object> body = (Map<String, Object>) captor.getValue();

        // Defaults que el form del FE no captura pero el dominio exige:
        assertThat(body).containsEntry("currency", "MXN")
                .containsEntry("amountStep", 1000)
                .containsEntry("defaultPaymentFrequency", "MONTHLY")
                .containsEntry("eligiblePartyTypes", Set.of("INDIVIDUAL"))
                .containsEntry("channelAvailabilities", Set.of("WEB"));
        assertThat((List<?>) body.get("requiredDocuments")).isNotEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void create_nullsFrequencyAndAmortization_forRevolvingB2B() {
        when(client.create(any())).thenReturn(product("DRAFT"));

        controller.create(new CreateProductRequest(
                "BRL-NEW", "BUSINESS_REVOLVING_LINE", "REVOLVING", "Línea PyME", "desc", "B2B",
                new BigDecimal("0.28"), new BigDecimal("0.50"), null, null, null,
                null, null, new BigDecimal("500000"), new BigDecimal("50000"), new BigDecimal("2000000"),
                null, 250, "MANUAL", new BigDecimal("0.015"), new BigDecimal("0.0")));

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(client).create(captor.capture());
        Map<String, Object> body = (Map<String, Object>) captor.getValue();

        assertThat(body.get("defaultPaymentFrequency")).isNull();
        assertThat(body.get("amortizationType")).isNull();
        assertThat(body).containsEntry("eligiblePartyTypes", Set.of("BUSINESS"));
    }

    @Test
    void activate_and_retire_delegate() {
        UUID id = UUID.randomUUID();
        when(client.activate(id)).thenReturn(product("ACTIVE"));
        when(client.deprecate(id)).thenReturn(product("DEPRECATED"));

        assertThat(controller.activate(id)).containsEntry("activeInApp", true);
        assertThat(controller.retire(id)).containsEntry("status", "DEPRECATED");
        verify(client).activate(id);
        verify(client).deprecate(id);
    }

    private static CreditProductResponse product(String status) {
        return new CreditProductResponse(
                UUID.randomUUID(), "PL-001", 1, "PERSONAL_LOAN", "INSTALLMENT", "Préstamo", "desc",
                status, "B2C", "MXN", new BigDecimal("0.32"), new BigDecimal("0.55"),
                3, 36, 12, new BigDecimal("5000"), new BigDecimal("150000"),
                null, null, null, 1000, "FRENCH", "MONTHLY", 200, "AUTOMATIC",
                new BigDecimal("0.01"), new BigDecimal("0.02"), Set.of("INDIVIDUAL"));
    }
}

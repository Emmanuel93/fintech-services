package com.fintech.channelbackoffice.infrastructure.adapter.in.api;

import com.fintech.channelbackoffice.infrastructure.adapter.out.client.CreditPortfolioClient.CreditAccountResponse;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.CreditProductClient.CreditProductResponse;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.OriginationClient.ApplicationResponse;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.PartyClient.PartyResponse;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El contrato con el frontend (shared-types) vive aquí: si estos mapeos derivan,
 * las pantallas rompen en silencio. Se fija por nombre de campo.
 */
class BackofficeViewsTest {

    @Test
    void client_matchesSharedTypesClient() {
        PartyResponse p = new PartyResponse(
                UUID.randomUUID(), UUID.randomUUID(), null, "INDIVIDUAL", "ACTIVE",
                "Juan", "García", "López", "GARJ900101HDFXXX01", "GARJ900101XXX",
                null, "BAJO", 720, null, null, Instant.parse("2026-06-01T00:00:00Z"));

        Map<String, Object> m = BackofficeViews.client(p);

        assertThat(m).containsKeys("partyId", "partyType", "status", "fullName", "curp", "rfc",
                "riskLevel", "totalScore", "assignedExecutiveId", "assignedExecutiveName", "createdAt");
        assertThat(m).containsEntry("fullName", "Juan García López")
                .containsEntry("partyType", "INDIVIDUAL")
                .containsEntry("riskLevel", "BAJO")
                .containsEntry("totalScore", 720);
    }

    @Test
    void account_matchesSharedTypesCreditAccount() {
        CreditAccountResponse a = new CreditAccountResponse(
                UUID.randomUUID(), "CTR-1", UUID.randomUUID(), "PL-001", "PERSONAL_LOAN", "INSTALLMENT", "ACTIVE",
                new BigDecimal("0.24"), 12, new BigDecimal("1000"), BigDecimal.ZERO, BigDecimal.ZERO, null,
                null, null, 45, "BAJO", null, null, null,
                4, 12, 5, null, null, null, null, null);

        Map<String, Object> m = BackofficeViews.account(a, "Juan García");

        assertThat(m).containsKeys("creditAccountId", "contractNumber", "obligorPartyId", "obligorName",
                "productType", "productBehavior", "status", "principalBalance", "totalDebt",
                "daysDelinquent", "ifrs9Stage", "assignedExecutiveId");
        assertThat(m).containsEntry("obligorName", "Juan García")
                .containsEntry("ifrs9Stage", "STAGE_2")   // 45 dpd → SICR
                .containsEntry("totalDebt", new BigDecimal("1000"));
    }

    @Test
    void application_matchesSharedTypesCreditApplication() {
        ApplicationResponse a = new ApplicationResponse(
                UUID.randomUUID(), "SOL-0001", UUID.randomUUID(), "DISTRIBUTOR_LINE", "MANUAL_REVIEW",
                new BigDecimal("50000"), 12,
                "UNDER_MANUAL_REVIEW", "MANUAL", "MEDIO", null, null,
                null, null, null, null, null, Instant.parse("2026-06-01T00:00:00Z"));

        Map<String, Object> m = BackofficeViews.application(a);

        assertThat(m).containsKeys("applicationId", "prospectId", "productType", "requestedAmount",
                "requestedTerm", "status", "approvalFlow", "riskLevel", "rejectionReason",
                "offeredAmount", "nominalRate", "cat", "createdAt");
        assertThat(m).containsEntry("status", "UNDER_MANUAL_REVIEW")
                .containsEntry("productType", "DISTRIBUTOR_LINE");
    }

    @Test
    void product_matchesSharedTypesProductDefinition() {
        CreditProductResponse p = new CreditProductResponse(
                UUID.randomUUID(), "PL-001", 2, "PERSONAL_LOAN", "INSTALLMENT", "Préstamo", "desc",
                "ACTIVE", "B2C", "MXN", new BigDecimal("0.32"), new BigDecimal("0.55"),
                3, 36, 12, new BigDecimal("5000"), new BigDecimal("150000"),
                null, null, null, 1000, "FRENCH", "MONTHLY", 200, "AUTOMATIC",
                new BigDecimal("0.01"), new BigDecimal("0.02"), Set.of("INDIVIDUAL"));

        Map<String, Object> m = BackofficeViews.product(p);

        assertThat(m).containsKeys("id", "productCode", "productVersion", "productType", "behavior",
                "name", "status", "targetAudience", "nominalRateAnnual", "minApprovalScore",
                "defaultApprovalFlow", "openingFeeRate", "activeInApp");
        assertThat(m).containsEntry("activeInApp", true)   // status ACTIVE
                .containsEntry("productVersion", 2)
                .containsEntry("targetAudience", "B2C");
    }

    @Test
    void ifrs9Stage_bucketsByDaysDelinquent() {
        assertThat(BackofficeViews.ifrs9Stage(0)).isEqualTo("STAGE_1");
        assertThat(BackofficeViews.ifrs9Stage(45)).isEqualTo("STAGE_2");
        assertThat(BackofficeViews.ifrs9Stage(120)).isEqualTo("STAGE_3");
    }
}

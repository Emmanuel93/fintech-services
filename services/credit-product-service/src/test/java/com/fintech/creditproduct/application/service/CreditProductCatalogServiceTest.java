package com.fintech.creditproduct.application.service;

import com.fintech.creditproduct.application.port.out.CreditProductDefinitionRepository;
import com.fintech.creditproduct.application.port.out.EligibilityRuleRepository;
import com.fintech.creditproduct.application.port.out.ProductEventPublisher;
import com.fintech.creditproduct.application.port.out.RateCardRepository;
import com.fintech.creditproduct.domain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CreditProductCatalogServiceTest {

    @Mock CreditProductDefinitionRepository repository;
    @Mock RateCardRepository rateCardRepository;
    @Mock EligibilityRuleRepository eligibilityRuleRepository;
    @Mock ProductEventPublisher eventPublisher;

    @InjectMocks
    CreditProductCatalogService service;

    private static final Set<String> PARTY_TYPES = Set.of("INDIVIDUAL");
    private static final Set<RequiredDocument> DOCUMENTS = Set.of(
            new RequiredDocument("INCOME_PROOF", true),
            new RequiredDocument("ADDRESS_PROOF", true));
    private static final Set<String> CHANNELS = Set.of("WEB", "MOBILE_APP");

    @BeforeEach
    void setUp() {
        lenient().when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(repository.findMaxVersionByProductCode(any())).thenReturn(0);
        lenient().when(repository.findActiveByProductCode(any())).thenReturn(Optional.empty());
        lenient().doNothing().when(eventPublisher).publishProductActivated(any());
        lenient().doNothing().when(eventPublisher).publishProductRetired(any());
    }

    // ── ProductBehavior ───────────────────────────────────────────────────────

    @Test
    void productType_revolvingTypes_haveRevolvingBehavior() {
        assertThat(ProductType.CREDIT_CARD.behavior).isEqualTo(ProductBehavior.REVOLVING);
        assertThat(ProductType.REVOLVING_LINE.behavior).isEqualTo(ProductBehavior.REVOLVING);
        assertThat(ProductType.DISTRIBUTOR_LINE.behavior).isEqualTo(ProductBehavior.REVOLVING);
        assertThat(ProductType.BUSINESS_REVOLVING_LINE.behavior).isEqualTo(ProductBehavior.REVOLVING);
    }

    @Test
    void productType_installmentTypes_haveInstallmentBehavior() {
        assertThat(ProductType.PERSONAL_LOAN.behavior).isEqualTo(ProductBehavior.INSTALLMENT);
        assertThat(ProductType.PAYROLL_LOAN.behavior).isEqualTo(ProductBehavior.INSTALLMENT);
        assertThat(ProductType.GROUP_LOAN.behavior).isEqualTo(ProductBehavior.INSTALLMENT);
        assertThat(ProductType.MICRO_LOAN.behavior).isEqualTo(ProductBehavior.INSTALLMENT);
        assertThat(ProductType.SME_LOAN.behavior).isEqualTo(ProductBehavior.INSTALLMENT);
    }

    // ── Capabilities defaults ─────────────────────────────────────────────────

    @Test
    void capabilities_defaultFor_personalLoan_installmentSelfUse() {
        Capabilities c = Capabilities.defaultFor(ProductType.PERSONAL_LOAN);
        assertThat(c.hasAmortizationSchedule()).isTrue();
        assertThat(c.hasCreditLimit()).isFalse();
        assertThat(c.dispositionType()).isEqualTo("SELF_USE");
        assertThat(c.commissionsEnabled()).isFalse();
    }

    @Test
    void capabilities_defaultFor_distributorLine_thirdPartyWithCommissions() {
        Capabilities c = Capabilities.defaultFor(ProductType.DISTRIBUTOR_LINE);
        assertThat(c.hasCreditLimit()).isTrue();
        assertThat(c.allowsMultipleDispositions()).isTrue();
        assertThat(c.dispositionType()).isEqualTo("THIRD_PARTY_CREDIT");
        assertThat(c.commissionsEnabled()).isTrue();
        assertThat(c.requiresBeneficiaryPartyId()).isTrue();
    }

    @Test
    void capabilities_defaultFor_revolvingLine_cutoffAndMinPayment() {
        Capabilities c = Capabilities.defaultFor(ProductType.REVOLVING_LINE);
        assertThat(c.hasCreditLimit()).isTrue();
        assertThat(c.hasCutoffDate()).isTrue();
        assertThat(c.hasMinimumPayment()).isTrue();
        assertThat(c.hasAmortizationSchedule()).isFalse();
    }

    @Test
    void capabilities_defaultFor_groupLoan_multipleObligors() {
        Capabilities c = Capabilities.defaultFor(ProductType.GROUP_LOAN);
        assertThat(c.allowsMultipleObligors()).isTrue();
        assertThat(c.hasAmortizationSchedule()).isTrue();
        assertThat(c.dispositionType()).isEqualTo("SELF_USE");
    }

    @Test
    void capabilities_defaultFor_smeLoan_installmentSelfUse() {
        Capabilities c = Capabilities.defaultFor(ProductType.SME_LOAN);
        assertThat(c.hasAmortizationSchedule()).isTrue();
        assertThat(c.hasCreditLimit()).isFalse();
        assertThat(c.dispositionType()).isEqualTo("SELF_USE");
    }

    @Test
    void capabilities_defaultFor_businessRevolvingLine_revolvingNoCommissions() {
        Capabilities c = Capabilities.defaultFor(ProductType.BUSINESS_REVOLVING_LINE);
        assertThat(c.hasCreditLimit()).isTrue();
        assertThat(c.allowsMultipleDispositions()).isTrue();
        assertThat(c.commissionsEnabled()).isFalse();
        assertThat(c.requiresBeneficiaryPartyId()).isFalse();
    }

    // ── create — versioning ───────────────────────────────────────────────────

    @Test
    void create_firstVersion_startsAtVersion1() {
        when(repository.findMaxVersionByProductCode("PL-TEST")).thenReturn(0);
        CreditProductDefinition result = createPersonalLoan("PL-TEST");
        assertThat(result.getProductVersion()).isEqualTo(1);
        assertThat(result.getStatus()).isEqualTo(ProductStatus.DRAFT);
    }

    @Test
    void create_secondVersion_incrementsToVersion2() {
        when(repository.findMaxVersionByProductCode("PL-TEST")).thenReturn(1);
        CreditProductDefinition result = createPersonalLoan("PL-TEST");
        assertThat(result.getProductVersion()).isEqualTo(2);
    }

    @Test
    void create_whenCapabilitiesNull_usesDefaultForProductType() {
        when(repository.findMaxVersionByProductCode("PL-TEST")).thenReturn(0);
        CreditProductDefinition result = createPersonalLoan("PL-TEST");
        assertThat(result.getCapabilities()).isNotNull();
        assertThat(result.getCapabilities().hasAmortizationSchedule()).isTrue();
        assertThat(result.getCapabilities().dispositionType()).isEqualTo("SELF_USE");
    }

    // ── create — INSTALLMENT validations ─────────────────────────────────────

    @Test
    void create_personalLoan_savedAsDraftWithAmortization() {
        when(repository.findMaxVersionByProductCode("PL-V1")).thenReturn(0);
        CreditProductDefinition result = createPersonalLoan("PL-V1");
        assertThat(result.getStatus()).isEqualTo(ProductStatus.DRAFT);
        assertThat(result.getProductType()).isEqualTo(ProductType.PERSONAL_LOAN);
        assertThat(result.getBehavior()).isEqualTo(ProductBehavior.INSTALLMENT);
        assertThat(result.getAmortizationType()).isEqualTo(AmortizationType.FRENCH);
    }

    @Test
    void create_smeLoan_b2b_installment() {
        when(repository.findMaxVersionByProductCode("SME-TEST")).thenReturn(0);
        CreditProductDefinition result = createSmeLoan("SME-TEST");
        assertThat(result.getProductType()).isEqualTo(ProductType.SME_LOAN);
        assertThat(result.getBehavior()).isEqualTo(ProductBehavior.INSTALLMENT);
        assertThat(result.getTargetAudience()).isEqualTo(TargetAudience.B2B);
    }

    @Test
    void create_businessRevolvingLine_b2b_revolving() {
        when(repository.findMaxVersionByProductCode("BRL-TEST")).thenReturn(0);
        CreditProductDefinition result = createBusinessRevolvingLine("BRL-TEST");
        assertThat(result.getProductType()).isEqualTo(ProductType.BUSINESS_REVOLVING_LINE);
        assertThat(result.getBehavior()).isEqualTo(ProductBehavior.REVOLVING);
        assertThat(result.getTargetAudience()).isEqualTo(TargetAudience.B2B);
    }

    @Test
    void create_installmentWithoutTerm_throwsIllegalArgument() {
        assertThatThrownBy(() -> service.create(
                "PL-NO-TERM", ProductType.PERSONAL_LOAN, "Test", null,
                TargetAudience.B2C, "MXN",
                new BigDecimal("0.32"), new BigDecimal("0.55"),
                null, null, null,
                new BigDecimal("5000"), new BigDecimal("150000"),
                null, null, null,
                null, null,
                AmortizationType.FRENCH, PaymentFrequency.MONTHLY,
                Set.of(PaymentFrequency.MONTHLY),
                200, ApprovalFlow.AUTOMATIC,
                new BigDecimal("0.01"), new BigDecimal("0.02"),
                PARTY_TYPES, DOCUMENTS, CHANNELS, null, null, null
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("minTerm");
    }

    @Test
    void create_revolvingWithoutCreditLine_throwsIllegalArgument() {
        assertThatThrownBy(() -> service.create(
                "RL-NO-LINE", ProductType.REVOLVING_LINE, "Test", null,
                TargetAudience.B2C, "MXN",
                new BigDecimal("0.38"), new BigDecimal("0.60"),
                null, null, null, null, null, null, null, null,
                null, null,
                null, PaymentFrequency.MONTHLY, Set.of(),
                250, ApprovalFlow.AUTOMATIC,
                new BigDecimal("0.01"), BigDecimal.ZERO,
                PARTY_TYPES, DOCUMENTS, CHANNELS, null, null, null
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("defaultCreditLine");
    }

    // ── amountStep ────────────────────────────────────────────────────────────

    @Test
    void create_personalLoan_hasAmountStep1000() {
        when(repository.findMaxVersionByProductCode("PL-STEP")).thenReturn(0);
        CreditProductDefinition result = createPersonalLoan("PL-STEP");
        assertThat(result.getAmountStep()).isEqualTo(1000);
        assertThat(result.isValidAmount(new BigDecimal("50000"))).isTrue();
        assertThat(result.isValidAmount(new BigDecimal("50100"))).isFalse();  // not a multiple of 1000
        assertThat(result.roundDownToStep(new BigDecimal("50999"))).isEqualByComparingTo("50000");
    }

    @Test
    void create_smeLoan_hasAmountStep5000() {
        when(repository.findMaxVersionByProductCode("SME-STEP")).thenReturn(0);
        CreditProductDefinition result = createSmeLoan("SME-STEP");
        assertThat(result.getAmountStep()).isEqualTo(5000);
        assertThat(result.isValidAmount(new BigDecimal("50000"))).isTrue();
        assertThat(result.isValidAmount(new BigDecimal("51000"))).isFalse();  // not a multiple of 5000
        assertThat(result.roundDownToStep(new BigDecimal("54999"))).isEqualByComparingTo("50000");
    }

    @Test
    void amountStep_null_alwaysValid() {
        // Null step = no constraint
        CreditProductDefinition def = CreditProductDefinition.create(
                "NO-STEP", 1, ProductType.PERSONAL_LOAN, "Test", null,
                TargetAudience.B2C, "MXN",
                new BigDecimal("0.32"), new BigDecimal("0.55"),
                3, 36, 12, new BigDecimal("5000"), new BigDecimal("150000"),
                null, null, null,
                null, null,    // amountStep = null, termStep = null → 1
                AmortizationType.FRENCH, PaymentFrequency.MONTHLY,
                Set.of(PaymentFrequency.MONTHLY),
                200, ApprovalFlow.AUTOMATIC,
                new BigDecimal("0.01"), new BigDecimal("0.02"),
                PARTY_TYPES, DOCUMENTS, CHANNELS, null);
        assertThat(def.isValidAmount(new BigDecimal("50123.45"))).isTrue();
        assertThat(def.roundDownToStep(new BigDecimal("50123.45")))
                .isEqualByComparingTo("50123.45");
    }

    // ── activate — versioning (PD-01/PD-02) ──────────────────────────────────

    @Test
    void activate_draftProduct_becomesActive() {
        when(repository.findMaxVersionByProductCode("PL-DRAFT")).thenReturn(0);
        CreditProductDefinition draft = createPersonalLoan("PL-DRAFT");
        UUID id = draft.getProductDefinitionId();
        when(repository.findById(id)).thenReturn(Optional.of(draft));
        when(repository.findActiveByProductCode("PL-DRAFT")).thenReturn(Optional.empty());

        CreditProductDefinition result = service.activate(id);

        assertThat(result.getStatus()).isEqualTo(ProductStatus.ACTIVE);
        assertThat(result.getActivatedAt()).isNotNull();
        verify(eventPublisher).publishProductActivated(any());
    }

    @Test
    void activate_retiresPreviousActiveVersion() {
        // Create two versions
        when(repository.findMaxVersionByProductCode("PL-MULTI")).thenReturn(0);
        CreditProductDefinition v1 = createPersonalLoan("PL-MULTI");
        v1.activate();

        when(repository.findMaxVersionByProductCode("PL-MULTI")).thenReturn(1);
        CreditProductDefinition v2 = createPersonalLoan("PL-MULTI");

        UUID v2Id = v2.getProductDefinitionId();
        when(repository.findById(v2Id)).thenReturn(Optional.of(v2));
        when(repository.findActiveByProductCode("PL-MULTI")).thenReturn(Optional.of(v1));

        service.activate(v2Id);

        assertThat(v1.getStatus()).isEqualTo(ProductStatus.RETIRED);
        assertThat(v1.getRetiredAt()).isNotNull();
        assertThat(v2.getStatus()).isEqualTo(ProductStatus.ACTIVE);
        verify(eventPublisher).publishProductRetired(any());
        verify(eventPublisher).publishProductActivated(any());
    }

    @Test
    void activate_notFound_throwsNotFound() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.activate(id))
                .isInstanceOf(CreditProductNotFoundException.class);
    }

    @Test
    void activate_alreadyActive_throwsInvalidTransition() {
        when(repository.findMaxVersionByProductCode("PL-ACTIVE")).thenReturn(0);
        CreditProductDefinition draft = createPersonalLoan("PL-ACTIVE");
        UUID id = draft.getProductDefinitionId();
        draft.activate();
        when(repository.findById(id)).thenReturn(Optional.of(draft));
        assertThatThrownBy(() -> service.activate(id))
                .isInstanceOf(InvalidProductTransitionException.class);
    }

    // ── retire ────────────────────────────────────────────────────────────────

    @Test
    void retire_activeProduct_becomesRetired() {
        when(repository.findMaxVersionByProductCode("PL-RET")).thenReturn(0);
        CreditProductDefinition product = createPersonalLoan("PL-RET");
        product.activate();
        when(repository.findActiveByProductCode("PL-RET")).thenReturn(Optional.of(product));

        CreditProductDefinition result = service.retire("PL-RET");

        assertThat(result.getStatus()).isEqualTo(ProductStatus.RETIRED);
        assertThat(result.getRetiredAt()).isNotNull();
    }

    @Test
    void retire_noActiveVersion_throwsNotFound() {
        when(repository.findActiveByProductCode("PL-NONE")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.retire("PL-NONE"))
                .isInstanceOf(CreditProductNotFoundException.class);
    }

    // ── deactivate ────────────────────────────────────────────────────────────

    @Test
    void deactivate_activeProduct_becomesInactive() {
        when(repository.findMaxVersionByProductCode("PL-DEACT")).thenReturn(0);
        CreditProductDefinition product = createPersonalLoan("PL-DEACT");
        product.activate();
        UUID id = product.getProductDefinitionId();
        when(repository.findById(id)).thenReturn(Optional.of(product));

        CreditProductDefinition result = service.deactivate(id);
        assertThat(result.getStatus()).isEqualTo(ProductStatus.INACTIVE);
    }

    // ── RateCard resolution ───────────────────────────────────────────────────

    @Test
    void rateCard_matches_whenAllBandsContainValue() {
        RateCard rc = RateCard.create(UUID.randomUUID(), "T1",
                new BigDecimal("5000"), new BigDecimal("50000"),
                6, 24, new BigDecimal("0.28"), new BigDecimal("0.50"));
        assertThat(rc.matches("T1", new BigDecimal("20000"), 12)).isTrue();
        assertThat(rc.matches("T2", new BigDecimal("20000"), 12)).isFalse(); // wrong tier
        assertThat(rc.matches("T1", new BigDecimal("100000"), 12)).isFalse(); // above maxAmount
        assertThat(rc.matches("T1", new BigDecimal("20000"), 36)).isFalse(); // above maxTerm
    }

    @Test
    void rateCard_specificity_countsNonNullBands() {
        RateCard rcFull = RateCard.create(UUID.randomUUID(), "T1",
                new BigDecimal("1000"), new BigDecimal("50000"), 6, 24,
                new BigDecimal("0.28"), new BigDecimal("0.50"));
        RateCard rcTierOnly = RateCard.create(UUID.randomUUID(), "T1",
                null, null, null, null,
                new BigDecimal("0.28"), new BigDecimal("0.50"));
        assertThat(rcFull.specificity()).isGreaterThan(rcTierOnly.specificity());
    }

    // ── EligibilityRule ───────────────────────────────────────────────────────

    @Test
    void eligibilityRule_numeric_evaluatesCorrectly() {
        EligibilityRule rule = EligibilityRule.numeric(UUID.randomUUID(),
                EligibilityRuleType.MIN_SCORE, EligibilityOperator.GTE,
                new BigDecimal("200"), "ELIG_MIN_SCORE");
        assertThat(rule.evaluate(new BigDecimal("250"))).isTrue();
        assertThat(rule.evaluate(new BigDecimal("150"))).isFalse();
    }

    @Test
    void eligibilityRule_partyType_evaluatesCorrectly() {
        EligibilityRule rule = EligibilityRule.partyType(UUID.randomUUID(),
                "BUSINESS", "ELIG_PARTY_TYPE_REQUIRED");
        assertThat(rule.evaluatePartyType("BUSINESS")).isTrue();
        assertThat(rule.evaluatePartyType("INDIVIDUAL")).isFalse();
    }

    // ── queries ───────────────────────────────────────────────────────────────

    @Test
    void findActive_noFilters_callsFindByStatus() {
        service.findActive(null, null);
        verify(repository).findByStatus(ProductStatus.ACTIVE);
    }

    @Test
    void findActive_b2bAudience_callsWithAudienceFilter() {
        service.findActive(null, TargetAudience.B2B);
        verify(repository).findByStatusAndTargetAudience(ProductStatus.ACTIVE, TargetAudience.B2B);
    }

    @Test
    void findActive_b2b2cAndDistributorLine_callsWithBothFilters() {
        service.findActive(ProductType.DISTRIBUTOR_LINE, TargetAudience.B2B2C);
        verify(repository).findByStatusAndProductTypeAndTargetAudience(
                ProductStatus.ACTIVE, ProductType.DISTRIBUTOR_LINE, TargetAudience.B2B2C);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private CreditProductDefinition createPersonalLoan(String code) {
        return service.create(
                code, ProductType.PERSONAL_LOAN, "Test PL", null,
                TargetAudience.B2C, "MXN",
                new BigDecimal("0.32"), new BigDecimal("0.55"),
                3, 36, 12,
                new BigDecimal("5000"), new BigDecimal("150000"),
                null, null, null,
                1000, null,
                AmortizationType.FRENCH, PaymentFrequency.MONTHLY,
                Set.of(PaymentFrequency.MONTHLY, PaymentFrequency.BIWEEKLY),
                200, ApprovalFlow.AUTOMATIC,
                new BigDecimal("0.01"), new BigDecimal("0.02"),
                PARTY_TYPES, DOCUMENTS, CHANNELS, null, null, null);
    }

    private CreditProductDefinition createSmeLoan(String code) {
        return service.create(
                code, ProductType.SME_LOAN, "Test SME", null,
                TargetAudience.B2B, "MXN",
                new BigDecimal("0.24"), new BigDecimal("0.40"),
                6, 48, 24,
                new BigDecimal("50000"), new BigDecimal("5000000"),
                null, null, null,
                5000, null,
                AmortizationType.FRENCH, PaymentFrequency.MONTHLY,
                Set.of(PaymentFrequency.MONTHLY),
                300, ApprovalFlow.COMMITTEE,
                new BigDecimal("0.015"), new BigDecimal("0.03"),
                Set.of("BUSINESS"), DOCUMENTS, Set.of("BRANCH", "API_PARTNER"),
                null, null, null);
    }

    private CreditProductDefinition createBusinessRevolvingLine(String code) {
        return service.create(
                code, ProductType.BUSINESS_REVOLVING_LINE, "Test BRL", null,
                TargetAudience.B2B, "MXN",
                new BigDecimal("0.18"), new BigDecimal("0.32"),
                null, null, null, null, null,
                new BigDecimal("500000"), new BigDecimal("100000"), new BigDecimal("10000000"),
                10000, null,
                null, PaymentFrequency.MONTHLY,
                Set.of(PaymentFrequency.MONTHLY),
                350, ApprovalFlow.MANUAL,
                BigDecimal.ZERO, BigDecimal.ZERO,
                Set.of("BUSINESS"), DOCUMENTS, Set.of("BRANCH", "API_PARTNER"),
                null, null, null);
    }
}

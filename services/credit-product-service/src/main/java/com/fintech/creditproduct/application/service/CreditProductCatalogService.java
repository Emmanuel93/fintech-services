package com.fintech.creditproduct.application.service;

import com.fintech.creditproduct.application.port.out.CreditProductDefinitionRepository;
import com.fintech.creditproduct.application.port.out.EligibilityRuleRepository;
import com.fintech.creditproduct.application.port.out.ProductEventPublisher;
import com.fintech.creditproduct.application.port.out.RateCardRepository;
import com.fintech.creditproduct.domain.*;
import com.fintech.creditproduct.domain.event.ProductActivatedEvent;
import com.fintech.creditproduct.domain.event.ProductRetiredEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class CreditProductCatalogService {

    private static final Logger log = LoggerFactory.getLogger(CreditProductCatalogService.class);

    private final CreditProductDefinitionRepository repository;
    private final RateCardRepository rateCardRepository;
    private final EligibilityRuleRepository eligibilityRuleRepository;
    private final ProductEventPublisher eventPublisher;

    public CreditProductCatalogService(
            CreditProductDefinitionRepository repository,
            RateCardRepository rateCardRepository,
            EligibilityRuleRepository eligibilityRuleRepository,
            ProductEventPublisher eventPublisher) {
        this.repository               = repository;
        this.rateCardRepository       = rateCardRepository;
        this.eligibilityRuleRepository = eligibilityRuleRepository;
        this.eventPublisher           = eventPublisher;
    }

    /**
     * Creates a new product definition version.
     *
     * <p>If a definition with the same productCode already exists, the new one gets
     * version = maxExistingVersion + 1.  Otherwise it starts at version 1.
     * The new definition is saved in DRAFT status — call {@link #activate(UUID)} to publish it.
     */
    @Transactional
    public CreditProductDefinition create(
            String productCode,
            ProductType productType, String name, String description,
            TargetAudience targetAudience, String currency,
            BigDecimal nominalRateAnnual, BigDecimal moratoriumRateAnnual,
            Integer minTerm, Integer maxTerm, Integer defaultTerm,
            BigDecimal minAmount, BigDecimal maxAmount,
            BigDecimal defaultCreditLine, BigDecimal minCreditLine, BigDecimal maxCreditLine,
            Integer amountStep, Integer termStep,
            AmortizationType amortizationType,
            PaymentFrequency defaultPaymentFrequency,
            Set<PaymentFrequency> allowedPaymentFrequencies,
            Integer minApprovalScore, ApprovalFlow defaultApprovalFlow,
            BigDecimal openingFeeRate, BigDecimal prepaymentFeeRate,
            Set<String> eligiblePartyTypes, Set<RequiredDocument> requiredDocuments,
            Set<String> channelAvailabilities,
            Capabilities capabilities,
            List<RateCard> rateCards,
            List<EligibilityRule> eligibilityRules
    ) {
        validateStructuralFields(productType, minTerm, maxTerm, defaultTerm,
                minAmount, maxAmount, defaultCreditLine, minCreditLine, maxCreditLine);
        validateInstallmentFields(productType, amortizationType, defaultPaymentFrequency, allowedPaymentFrequencies);

        int nextVersion = repository.findMaxVersionByProductCode(productCode) + 1;

        CreditProductDefinition definition = CreditProductDefinition.create(
                productCode, nextVersion, productType, name, description,
                targetAudience, currency,
                nominalRateAnnual, moratoriumRateAnnual,
                minTerm, maxTerm, defaultTerm,
                minAmount, maxAmount,
                defaultCreditLine, minCreditLine, maxCreditLine,
                amountStep, termStep,
                amortizationType, defaultPaymentFrequency, allowedPaymentFrequencies,
                minApprovalScore, defaultApprovalFlow,
                openingFeeRate, prepaymentFeeRate,
                eligiblePartyTypes, requiredDocuments, channelAvailabilities,
                capabilities
        );

        CreditProductDefinition saved = repository.save(definition);

        if (rateCards != null) {
            rateCards.forEach(rc -> rateCardRepository.save(
                    RateCard.create(saved.getProductDefinitionId(),
                            rc.getTierBand(), rc.getMinAmount(), rc.getMaxAmount(),
                            rc.getMinTerm(), rc.getMaxTerm(),
                            rc.getNominalRate(), rc.getMoratoriumRate())));
        }
        if (eligibilityRules != null) {
            eligibilityRules.forEach(er -> {
                if (er.getRuleType() == EligibilityRuleType.REQUIRED_PARTY_TYPE) {
                    eligibilityRuleRepository.save(
                            EligibilityRule.partyType(saved.getProductDefinitionId(),
                                    er.getStringValue(), er.getErrorCode()));
                } else {
                    eligibilityRuleRepository.save(
                            EligibilityRule.numeric(saved.getProductDefinitionId(),
                                    er.getRuleType(), er.getOperator(),
                                    er.getThresholdValue(), er.getErrorCode()));
                }
            });
        }

        log.info("Credit product created: id={} code={} version={} type={} status=DRAFT",
                saved.getProductDefinitionId(), saved.getProductCode(),
                saved.getProductVersion(), saved.getProductType());
        return saved;
    }

    /**
     * Activates a DRAFT definition.
     *
     * <p>PD-01+PD-02: if another version of the same productCode is currently ACTIVE, it is
     * automatically RETIRED before the new version becomes ACTIVE (one ACTIVE per code).
     */
    @Transactional
    public CreditProductDefinition activate(UUID productDefinitionId) {
        CreditProductDefinition definition = findByIdOrThrow(productDefinitionId);

        // Retire previous ACTIVE version of the same code (PD-01 / PD-02)
        repository.findActiveByProductCode(definition.getProductCode()).ifPresent(previous -> {
            if (!previous.getProductDefinitionId().equals(productDefinitionId)) {
                int previousVersion = previous.getProductVersion();
                previous.retire();
                repository.save(previous);
                // PD-01: flush the RETIRE before the new version goes ACTIVE so the partial
                // unique index (WHERE status='ACTIVE') never sees two ACTIVE rows at flush time.
                repository.flush();
                log.info("Previous version retired: code={} version={}", previous.getProductCode(), previousVersion);
                try {
                    eventPublisher.publishProductRetired(new ProductRetiredEvent(
                            previous.getProductDefinitionId(),
                            previous.getProductCode(),
                            previousVersion,
                            definition.getProductVersion(),
                            Instant.now()));
                } catch (Exception e) {
                    log.warn("Failed to publish ProductRetiredEvent for code={} — non-fatal", previous.getProductCode(), e);
                }
            }
        });

        definition.activate();
        CreditProductDefinition saved = repository.save(definition);

        try {
            eventPublisher.publishProductActivated(new ProductActivatedEvent(
                    saved.getProductDefinitionId(),
                    saved.getProductCode(),
                    saved.getProductVersion(),
                    saved.getProductType().name(),
                    saved.getBehavior().name(),
                    saved.getTargetAudience().name(),
                    saved.getNominalRateAnnual(),
                    saved.getMoratoriumRateAnnual(),
                    saved.getCapabilities(),
                    saved.getActivatedAt()));
        } catch (Exception e) {
            log.warn("Failed to publish ProductActivatedEvent for code={} — non-fatal", saved.getProductCode(), e);
        }

        log.info("Credit product activated: id={} code={} version={}", saved.getProductDefinitionId(),
                saved.getProductCode(), saved.getProductVersion());
        return saved;
    }

    @Transactional
    public CreditProductDefinition retire(String productCode) {
        CreditProductDefinition active = repository.findActiveByProductCode(productCode)
                .orElseThrow(() -> new CreditProductNotFoundException("No ACTIVE version for code: " + productCode));
        active.retire();
        CreditProductDefinition saved = repository.save(active);
        try {
            eventPublisher.publishProductRetired(new ProductRetiredEvent(
                    saved.getProductDefinitionId(), saved.getProductCode(),
                    saved.getProductVersion(), 0, Instant.now()));
        } catch (Exception e) {
            log.warn("Failed to publish ProductRetiredEvent — non-fatal", e);
        }
        log.info("Credit product retired: code={} version={}", saved.getProductCode(), saved.getProductVersion());
        return saved;
    }

    @Transactional
    public CreditProductDefinition deactivate(UUID productDefinitionId) {
        CreditProductDefinition definition = findByIdOrThrow(productDefinitionId);
        definition.deactivate();
        return repository.save(definition);
    }

    @Transactional
    public CreditProductDefinition reactivate(UUID productDefinitionId) {
        CreditProductDefinition definition = findByIdOrThrow(productDefinitionId);
        // Retiring the current ACTIVE if there is one
        repository.findActiveByProductCode(definition.getProductCode()).ifPresent(previous -> {
            if (!previous.getProductDefinitionId().equals(productDefinitionId)) {
                previous.retire();
                repository.save(previous);
                repository.flush();   // PD-01: order RETIRE before reactivation
            }
        });
        definition.reactivate();
        return repository.save(definition);
    }

    @Transactional
    public CreditProductDefinition deprecate(UUID productDefinitionId) {
        CreditProductDefinition definition = findByIdOrThrow(productDefinitionId);
        definition.deprecate();
        return repository.save(definition);
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Optional<CreditProductDefinition> findById(UUID productDefinitionId) {
        return repository.findById(productDefinitionId);
    }

    @Transactional(readOnly = true)
    public Optional<CreditProductDefinition> findActiveByProductCode(String productCode) {
        return repository.findActiveByProductCode(productCode);
    }

    /** Catálogo completo (cualquier estado) para la administración del backoffice. */
    @Transactional(readOnly = true)
    public List<CreditProductDefinition> findAll() {
        return repository.findAll();
    }

    @Transactional(readOnly = true)
    public Optional<CreditProductDefinition> findByProductCode(String productCode) {
        return repository.findActiveByProductCode(productCode)
                .or(() -> repository.findByProductCode(productCode));
    }

    @Transactional(readOnly = true)
    public List<CreditProductDefinition> findVersionHistory(String productCode) {
        return repository.findVersionHistory(productCode);
    }

    @Transactional(readOnly = true)
    public List<CreditProductDefinition> findActive(ProductType productType, TargetAudience targetAudience) {
        if (productType != null && targetAudience != null) {
            return repository.findByStatusAndProductTypeAndTargetAudience(
                    ProductStatus.ACTIVE, productType, targetAudience);
        }
        if (productType != null) {
            return repository.findByStatusAndProductType(ProductStatus.ACTIVE, productType);
        }
        if (targetAudience != null) {
            return repository.findByStatusAndTargetAudience(ProductStatus.ACTIVE, targetAudience);
        }
        return repository.findByStatus(ProductStatus.ACTIVE);
    }

    @Transactional(readOnly = true)
    public List<RateCard> findRateCards(UUID productDefinitionId) {
        return rateCardRepository.findByProductDefinitionId(productDefinitionId);
    }

    @Transactional(readOnly = true)
    public List<EligibilityRule> findEligibilityRules(UUID productDefinitionId) {
        return eligibilityRuleRepository.findByProductDefinitionId(productDefinitionId);
    }

    // ── Validation ────────────────────────────────────────────────────────────

    private void validateStructuralFields(
            ProductType productType,
            Integer minTerm, Integer maxTerm, Integer defaultTerm,
            BigDecimal minAmount, BigDecimal maxAmount,
            BigDecimal defaultCreditLine, BigDecimal minCreditLine, BigDecimal maxCreditLine) {

        if (productType.isRevolving()) {
            if (defaultCreditLine == null || minCreditLine == null || maxCreditLine == null) {
                throw new IllegalArgumentException(
                        productType + " (REVOLVING) requires defaultCreditLine, minCreditLine and maxCreditLine");
            }
        } else {
            if (minTerm == null || maxTerm == null || defaultTerm == null) {
                throw new IllegalArgumentException(
                        productType + " (INSTALLMENT) requires minTerm, maxTerm and defaultTerm");
            }
            if (minAmount == null || maxAmount == null) {
                throw new IllegalArgumentException(
                        productType + " (INSTALLMENT) requires minAmount and maxAmount");
            }
        }
    }

    private void validateInstallmentFields(
            ProductType productType,
            AmortizationType amortizationType,
            PaymentFrequency defaultPaymentFrequency,
            Set<PaymentFrequency> allowedPaymentFrequencies) {

        if (productType.isInstallment()) {
            if (amortizationType == null) {
                throw new IllegalArgumentException(
                        productType + " (INSTALLMENT) requires amortizationType");
            }
            if (defaultPaymentFrequency == null) {
                throw new IllegalArgumentException(
                        productType + " (INSTALLMENT) requires defaultPaymentFrequency");
            }
            if (allowedPaymentFrequencies == null || allowedPaymentFrequencies.isEmpty()) {
                throw new IllegalArgumentException(
                        productType + " (INSTALLMENT) requires at least one allowedPaymentFrequency");
            }
        }
    }

    private CreditProductDefinition findByIdOrThrow(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new CreditProductNotFoundException(id.toString()));
    }
}

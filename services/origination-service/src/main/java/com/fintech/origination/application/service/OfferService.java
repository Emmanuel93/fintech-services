package com.fintech.origination.application.service;

import com.fintech.origination.application.CreditProductDefinition;
import com.fintech.origination.application.PresentOfferCommand;
import com.fintech.origination.application.port.in.AcceptOfferUseCase;
import com.fintech.origination.application.port.in.PresentOfferUseCase;
import com.fintech.origination.application.port.in.RejectOfferUseCase;
import com.fintech.origination.application.port.out.CreditApplicationRepository;
import com.fintech.origination.application.port.out.OfferEventPublisher;
import com.fintech.origination.application.port.out.ProductCatalogReader;
import com.fintech.origination.domain.CreditApplication;
import com.fintech.origination.domain.CreditApplicationNotFoundException;
import com.fintech.origination.domain.CreditOffer;
import com.fintech.origination.domain.event.OfferPresentedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
@Transactional
public class OfferService implements PresentOfferUseCase, AcceptOfferUseCase, RejectOfferUseCase {

    private static final Logger log = LoggerFactory.getLogger(OfferService.class);
    private static final long OFFER_TTL_HOURS = 72;

    private final CreditApplicationRepository applicationRepository;
    private final ProductCatalogReader catalogReader;
    private final OfferEventPublisher eventPublisher;

    public OfferService(CreditApplicationRepository applicationRepository,
                        ProductCatalogReader catalogReader,
                        OfferEventPublisher eventPublisher) {
        this.applicationRepository = applicationRepository;
        this.catalogReader         = catalogReader;
        this.eventPublisher        = eventPublisher;
    }

    @Override
    public CreditApplication present(PresentOfferCommand cmd) {
        CreditApplication app = loadOrThrow(cmd.applicationId());

        CreditProductDefinition product = catalogReader.findByCode(cmd.productCode())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Product not found in catalog: " + cmd.productCode()));

        validateProductTypeMatch(app, product);

        boolean revolving = "REVOLVING".equalsIgnoreCase(product.behavior());

        BigDecimal amount = resolveAmount(cmd, app, product, revolving);
        Integer term      = resolveTerm(cmd, app, product, revolving);

        BigDecimal cat = revolving
                ? CatCalculator.calculateRevolving(product.nominalRateAnnual())
                : CatCalculator.calculateInstallment(
                        amount, product.nominalRateAnnual(), term,
                        product.openingFeeRate());

        CreditOffer offer = CreditOffer.create(
                product.productCode(),
                product.productVersion(),
                product.behavior(),
                product.amortizationType(),
                revolving ? null : amount,
                revolving ? (cmd.offeredAmount() != null ? cmd.offeredAmount() : product.defaultCreditLine()) : null,
                revolving ? null : term,
                product.nominalRateAnnual(),
                product.moratoriumRateAnnual(),
                product.openingFeeRate() != null ? product.openingFeeRate() : BigDecimal.ZERO,
                cat,
                Instant.now().plus(OFFER_TTL_HOURS, ChronoUnit.HOURS));

        app.presentOffer(offer);
        CreditApplication saved = applicationRepository.save(app);
        log.info("Offer presented applicationId={} productCode={} cat={}", app.getApplicationId(), product.productCode(), cat);

        eventPublisher.publish(new OfferPresentedEvent(
                saved.getApplicationId(),
                saved.getProspectId(),
                offer.getProductCode(),
                offer.getOfferedAmount(),
                offer.getOfferedLine(),
                offer.getOfferedTerm(),
                offer.getNominalRate(),
                offer.getCat(),
                offer.getValidUntil()));

        return saved;
    }

    @Override
    public CreditApplication accept(UUID applicationId) {
        CreditApplication app = loadOrThrow(applicationId);
        app.acceptOffer();
        CreditApplication saved = applicationRepository.save(app);
        log.info("Offer accepted applicationId={}", applicationId);
        return saved;
    }

    @Override
    public CreditApplication reject(UUID applicationId) {
        CreditApplication app = loadOrThrow(applicationId);
        app.rejectOffer();
        CreditApplication saved = applicationRepository.save(app);
        log.info("Offer rejected applicationId={}", applicationId);
        return saved;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private CreditApplication loadOrThrow(UUID id) {
        return applicationRepository.findById(id)
                .orElseThrow(() -> new CreditApplicationNotFoundException(id.toString()));
    }

    private void validateProductTypeMatch(CreditApplication app, CreditProductDefinition product) {
        if (!app.getProductType().name().equalsIgnoreCase(product.productType())) {
            throw new IllegalArgumentException(
                    "Product " + product.productCode() + " has type " + product.productType()
                    + " but application expects " + app.getProductType());
        }
    }

    private BigDecimal resolveAmount(PresentOfferCommand cmd, CreditApplication app,
                                     CreditProductDefinition product, boolean revolving) {
        if (revolving) return null;
        BigDecimal amount = cmd.offeredAmount() != null ? cmd.offeredAmount() : app.getRequestedAmount();
        if (amount == null) amount = product.minAmount();
        if (product.maxAmount() != null && amount.compareTo(product.maxAmount()) > 0) {
            throw new IllegalArgumentException(
                    "offeredAmount " + amount + " exceeds product maxAmount " + product.maxAmount() + " (OM-02)");
        }
        if (product.minAmount() != null && amount.compareTo(product.minAmount()) < 0) {
            throw new IllegalArgumentException(
                    "offeredAmount " + amount + " below product minAmount " + product.minAmount());
        }
        return amount;
    }

    private Integer resolveTerm(PresentOfferCommand cmd, CreditApplication app,
                                CreditProductDefinition product, boolean revolving) {
        if (revolving) return null;
        if (cmd.offeredTerm() != null) return cmd.offeredTerm();
        if (app.getRequestedTerm() != null) return app.getRequestedTerm();
        return product.defaultTerm() != null ? product.defaultTerm() : product.minTerm();
    }
}

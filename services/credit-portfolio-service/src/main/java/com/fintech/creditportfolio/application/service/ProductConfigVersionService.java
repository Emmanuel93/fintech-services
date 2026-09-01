package com.fintech.creditportfolio.application.service;

import com.fintech.creditportfolio.application.port.out.ProductConfigVersionRepository;
import com.fintech.creditportfolio.domain.config.Capabilities;
import com.fintech.creditportfolio.domain.config.ProductConfigVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Maintains the local read-model of product config versions (ADR-001).
 *
 * <p>Idempotent upsert keyed by (productCode, productVersion): a version is immutable once
 * received from the authoritative event, so re-delivery overwrites with the same data and
 * clears any degraded fallback that may have been materialized during activation.
 */
@Service
@Transactional
public class ProductConfigVersionService {

    private static final Logger log = LoggerFactory.getLogger(ProductConfigVersionService.class);

    private final ProductConfigVersionRepository repository;

    public ProductConfigVersionService(ProductConfigVersionRepository repository) {
        this.repository = repository;
    }

    /** Upsert from the authoritative {@code product-catalog.product-activated} event. */
    public void upsertActivated(
            String productCode, int productVersion, String productType, String behavior,
            String targetAudience, Capabilities capabilities,
            String amortizationType, String paymentFrequency, Integer amountStep,
            BigDecimal nominalRateAnnual, BigDecimal moratoriumRateAnnual, BigDecimal openingFeeRate) {

        Capabilities caps = capabilities != null
                ? capabilities
                : Capabilities.degradedFor(behavior, null);

        // La configuración se guarda igual, pero no en silencio.
        //
        // Cartera no es quien autoriza un producto y rechazarlo aquí dejaría al catálogo y a la
        // cartera discrepando —que es justo el problema que ya costó una tarde—. Lo que sí puede
        // hacer es decirlo: una combinación contradictoria no rompe nada, sólo miente sobre lo que
        // el producto hace, y el síntoma aparece lejos y sin relación aparente. Con el WARN, el
        // día que alguien pregunte «por qué este producto no deja saltar pagos si el catálogo dice
        // que sí», la respuesta está en el log del arranque y no en una tarde de bisección.
        if (caps.opciones() != null) {
            for (String mal : caps.opciones().incoherencias()) {
                log.warn("Configuración contradictoria en {} v{}: {}", productCode, productVersion, mal);
            }
        }

        Optional<ProductConfigVersion> existing =
                repository.findByCodeAndVersion(productCode, productVersion);

        if (existing.isPresent()) {
            ProductConfigVersion v = existing.get();
            v.overwriteFrom(productType, behavior, targetAudience, caps, amortizationType,
                    paymentFrequency, amountStep, nominalRateAnnual, moratoriumRateAnnual,
                    openingFeeRate, "ACTIVE");
            repository.save(v);
            log.info("ProductConfigVersion updated code={} version={}", productCode, productVersion);
        } else {
            repository.save(ProductConfigVersion.of(
                    productCode, productVersion, productType, behavior, targetAudience, caps,
                    amortizationType, paymentFrequency, amountStep,
                    nominalRateAnnual, moratoriumRateAnnual, openingFeeRate,
                    "ACTIVE", false));
            log.info("ProductConfigVersion stored code={} version={}", productCode, productVersion);
        }
    }

    /**
     * Materializes a degraded config version from origination snapshot terms when the authoritative
     * event hasn't propagated. No-op if the version already exists (never overwrites authoritative
     * data). Returns the resolved version either way.
     */
    public ProductConfigVersion materializeDegraded(
            String productCode, int productVersion, String productType, String behavior,
            Capabilities capabilities, String amortizationType,
            BigDecimal nominalRateAnnual, BigDecimal moratoriumRateAnnual, BigDecimal openingFeeRate) {

        return repository.findByCodeAndVersion(productCode, productVersion).orElseGet(() -> {
            ProductConfigVersion v = repository.save(ProductConfigVersion.of(
                    productCode, productVersion, productType, behavior, null, capabilities,
                    amortizationType, null, null,
                    nominalRateAnnual, moratoriumRateAnnual, openingFeeRate,
                    "ACTIVE", true));   // degraded = true
            log.warn("ProductConfigVersion materialized DEGRADED code={} version={}", productCode, productVersion);
            return v;
        });
    }

    /** Marks a version RETIRED — it remains usable by accounts already pinned to it. */
    public void retire(String productCode, int retiredVersion) {
        repository.findByCodeAndVersion(productCode, retiredVersion).ifPresentOrElse(
                v -> {
                    v.markRetired();
                    repository.save(v);
                    log.info("ProductConfigVersion retired code={} version={}", productCode, retiredVersion);
                },
                () -> log.warn("Retire for unknown config version code={} version={} — ignored",
                        productCode, retiredVersion));
    }
}

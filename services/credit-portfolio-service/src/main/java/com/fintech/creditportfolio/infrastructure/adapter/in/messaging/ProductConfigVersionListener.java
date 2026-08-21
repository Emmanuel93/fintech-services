package com.fintech.creditportfolio.infrastructure.adapter.in.messaging;

import com.fintech.creditportfolio.application.service.ProductConfigVersionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Projects credit-product catalog events into the local config read-model.
 *
 * <p>Consumes:
 * <ul>
 *   <li>{@code product-catalog.product-activated} → upsert config version (authoritative)</li>
 *   <li>{@code product-catalog.product-retired} → mark version RETIRED</li>
 * </ul>
 */
@Component
public class ProductConfigVersionListener {

    private static final Logger log = LoggerFactory.getLogger(ProductConfigVersionListener.class);

    private final ProductConfigVersionService service;

    public ProductConfigVersionListener(ProductConfigVersionService service) {
        this.service = service;
    }

    @KafkaListener(
            topics = "product-catalog.product-activated",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "productActivatedListenerContainerFactory")
    public void onProductActivated(ProductActivatedPayload payload) {
        log.info("product-activated received code={} version={} behavior={}",
                payload.productCode(), payload.productVersion(), payload.behavior());

        service.upsertActivated(
                payload.productCode(),
                payload.productVersion(),
                payload.productType(),
                payload.behavior(),
                payload.targetAudience(),
                payload.capabilities(),
                payload.amortizationType(),
                payload.paymentFrequency(),
                payload.amountStep(),
                payload.nominalRateAnnual(),
                payload.moratoriumRateAnnual(),
                payload.openingFeeRate());
    }

    @KafkaListener(
            topics = "product-catalog.product-retired",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "productRetiredListenerContainerFactory")
    public void onProductRetired(ProductRetiredPayload payload) {
        log.info("product-retired received code={} retiredVersion={}",
                payload.productCode(), payload.retiredVersion());
        service.retire(payload.productCode(), payload.retiredVersion());
    }
}

package com.fintech.beneficiary.infrastructure.adapter.in.messaging;

import com.fintech.beneficiary.application.port.out.ProductKycPolicyRepository;
import com.fintech.beneficiary.domain.KycPolicy;
import com.fintech.beneficiary.domain.ProductKycPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Mantiene al día la política de KYC de cada producto (F3b).
 *
 * <p>El catálogo republica todos sus productos activos al arrancar
 * ({@code CreditProductCatalogService.republishCatalogOnStartup}), así que esta proyección se
 * rellena sola sin necesidad de sembrarla ni de una migración de datos: basta con que
 * credit-product arranque después de crear la tabla.
 *
 * <p><b>Un producto sin bloque de KYC no se guarda como «sin KYC».</b> Los publicados antes de F3b
 * no traen el campo, y escribir una fila permisiva por eso sería convertir un hueco de
 * configuración en una exención de verificación. Se registra la política segura —exigido y manual—
 * y se deja constancia en el log de que el catálogo todavía no la declara.
 */
@Component
public class ProductActivatedListener {

    private static final Logger log = LoggerFactory.getLogger(ProductActivatedListener.class);

    private final ProductKycPolicyRepository policies;

    public ProductActivatedListener(ProductKycPolicyRepository policies) {
        this.policies = policies;
    }

    @KafkaListener(
            topics = "product-catalog.product-activated",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "productActivatedListenerContainerFactory")
    @Transactional
    public void onProductActivated(ProductActivatedPayload event) {
        if (event == null || event.productCode() == null || event.productCode().isBlank()) return;

        KycPolicy policy = toPolicy(event);

        policies.findByProductCode(event.productCode())
                .ifPresentOrElse(
                        existing -> existing.refresh(event.productVersion(), policy),
                        () -> policies.save(ProductKycPolicy.of(
                                event.productCode(), event.productVersion(), policy)));

        log.info("Política de KYC de {} v{}: exigido={} automatización={} proveedor={}",
                event.productCode(), event.productVersion(),
                policy.kycRequired(), policy.automationEnabled(), policy.provider());
    }

    private KycPolicy toPolicy(ProductActivatedPayload event) {
        var caps = event.capabilities();
        var kyc  = caps == null ? null : caps.kyc();
        if (kyc == null) {
            log.warn("El producto {} v{} no declara política de KYC — se aplica la segura "
                     + "(exigido, manual). Declárala en Capabilities.kyc del catálogo.",
                     event.productCode(), event.productVersion());
            return KycPolicy.requiredManual();
        }
        return new KycPolicy(kyc.kycRequired(), kyc.amlRequired(), kyc.automationEnabled(),
                kyc.provider(), kyc.manualFallbackAllowed());
    }
}

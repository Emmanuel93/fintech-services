package com.fintech.beneficiary.application.service;

import com.fintech.beneficiary.application.port.out.ProductKycPolicyRepository;
import com.fintech.beneficiary.domain.KycPolicy;
import com.fintech.beneficiary.domain.Placement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Qué política de KYC aplica a una colocación (F3b).
 *
 * <p>Sale del producto de la línea contra la que se coloca, no de una propiedad del servicio. Si no
 * se puede resolver —la colocación es anterior a F3b y no guarda producto, o el catálogo todavía no
 * ha publicado ese producto— devuelve {@link KycPolicy#requiredManual()}.
 *
 * <p><b>Nunca devuelve una política permisiva por omisión.</b> Es la diferencia entre que un hueco
 * de configuración cueste trabajo de analista y que deje pasar a alguien sin verificar.
 */
@Service
public class KycPolicyResolver {

    private static final Logger log = LoggerFactory.getLogger(KycPolicyResolver.class);

    private final ProductKycPolicyRepository policies;

    public KycPolicyResolver(ProductKycPolicyRepository policies) {
        this.policies = policies;
    }

    public KycPolicy resolve(Placement placement) {
        return resolve(placement == null ? null : placement.getProductCode());
    }

    public KycPolicy resolve(String productCode) {
        if (productCode == null || productCode.isBlank()) {
            log.debug("Colocación sin producto — política segura (KYC exigido, manual)");
            return KycPolicy.requiredManual();
        }
        return policies.findByProductCode(productCode)
                .map(p -> honourAggregateInvariant(productCode, p.toPolicy()))
                .orElseGet(() -> {
                    log.warn("El catálogo no ha publicado la política de KYC de {} — se aplica la "
                             + "segura (exigido, manual)", productCode);
                    return KycPolicy.requiredManual();
                });
    }

    /**
     * Una colocación siempre exige identidad verificada, diga lo que diga el producto.
     *
     * <p>{@code kycRequired=false} es legítimo en el catálogo —habrá productos que no registren
     * gente— pero <b>aquí no se puede honrar</b>: {@code Placement.approve(...)} exige
     * {@code identityDecision = VERIFIED} —y además listas despejadas— y la base repite la primera
     * mitad en {@code chk_approved_requires_identity}.
     * Obedecer la bandera produciría colocaciones que nunca se pueden aprobar, o —peor, si alguien
     * quitara la restricción para «arreglarlo»— beneficiarias dadas de alta sin que nadie
     * comprobara que son ellas.
     *
     * <p>Así que gana el invariante y se dice en voz alta: es una contradicción de configuración
     * que alguien tiene que corregir en el catálogo, no un caso que este servicio deba resolver en
     * silencio.
     */
    private KycPolicy honourAggregateInvariant(String productCode, KycPolicy fromCatalog) {
        if (fromCatalog.kycRequired()) return fromCatalog;

        log.warn("El producto {} declara kycRequired=false y una colocación no puede aprobarse sin "
                 + "identidad verificada: se exige igual. Corrige la configuración del producto.",
                 productCode);
        return new KycPolicy(true, fromCatalog.amlRequired(), fromCatalog.automationEnabled(),
                fromCatalog.provider(), fromCatalog.manualFallbackAllowed());
    }
}

package com.fintech.creditportfolio.application.service;

import com.fintech.creditportfolio.application.CreateCreditAccountCommand;
import com.fintech.creditportfolio.application.port.out.ProductConfigVersionRepository;
import com.fintech.creditportfolio.domain.config.Capabilities;
import com.fintech.creditportfolio.domain.config.ProductConfigVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Resolves the product config version an account is pinned to (ADR-001: version-reference,
 * not frozen copy). The engine reads behaviour from the resolved version.
 *
 * <p>Resolution order:
 * <ol>
 *   <li>Exact pin {@code (productCode, productVersion)} from the read-model</li>
 *   <li>Latest ACTIVE version of the code (when the snapshot carried no version)</li>
 *   <li>Degraded fallback: materialize a version from the origination snapshot terms when the
 *       authoritative {@code product-activated} event has not propagated yet. The
 *       {@link ProductConfigVersionService} reconciles it (clears degraded) when the event arrives.</li>
 * </ol>
 */
@Service
@Transactional
public class ProductConfigResolver {

    private static final Logger log = LoggerFactory.getLogger(ProductConfigResolver.class);

    private final ProductConfigVersionRepository repository;
    private final ProductConfigVersionService configService;

    public ProductConfigResolver(ProductConfigVersionRepository repository,
                                 ProductConfigVersionService configService) {
        this.repository    = repository;
        this.configService = configService;
    }

    /** Resolves the config version for an account being activated from a snapshot. */
    public ProductConfigVersion resolveForActivation(CreateCreditAccountCommand cmd) {
        // 1. Exact pin
        if (cmd.productVersion() != null) {
            var pinned = repository.findByCodeAndVersion(cmd.productCode(), cmd.productVersion());
            if (pinned.isPresent()) {
                return pinned.get();
            }
            log.warn("Config version not propagated yet code={} version={} — materializing degraded",
                    cmd.productCode(), cmd.productVersion());
            return materializeDegraded(cmd, cmd.productVersion());
        }

        // 2. Latest ACTIVE
        var latest = repository.findLatestActiveByCode(cmd.productCode());
        if (latest.isPresent()) {
            return latest.get();
        }

        // 3. Degraded fallback with version 1
        log.warn("No config version for code={} — materializing degraded v1", cmd.productCode());
        return materializeDegraded(cmd, 1);
    }

    /**
     * La configuración de una cuenta que ya existe, por su pin ({@code productCode} +
     * {@code productVersion}).
     *
     * <p>A diferencia de {@link #resolveForActivation}, aquí <b>no se materializa nada degradado</b>:
     * la cuenta ya vive y fabricarle una configuración inventada sobre la marcha escribiría un plan
     * de pagos con términos que nadie aprobó. Si no está, el llamador decide qué hacer con un
     * {@code Optional} vacío — y lo que corresponde es caer al comportamiento anterior, no adivinar.
     */
    public Optional<ProductConfigVersion> resolveForAccount(String productCode, Integer productVersion) {
        if (productCode == null) return Optional.empty();
        if (productVersion != null) {
            var pinned = repository.findByCodeAndVersion(productCode, productVersion);
            if (pinned.isPresent()) return pinned;
            log.warn("Config pineada no encontrada code={} version={} — se intenta la última ACTIVE",
                    productCode, productVersion);
        }
        return repository.findLatestActiveByCode(productCode);
    }

    /**
     * Builds a degraded config version from the snapshot terms and persists it so the account
     * has something to pin. Capabilities are inferred from behavior; when the authoritative
     * event arrives the service overwrites this row and clears the degraded flag.
     */
    private ProductConfigVersion materializeDegraded(CreateCreditAccountCommand cmd, int version) {
        Capabilities caps = Capabilities.degradedFor(cmd.productBehavior(), null);
        return configService.materializeDegraded(
                cmd.productCode(), version, cmd.productType(), cmd.productBehavior(),
                caps, cmd.amortizationType(),
                cmd.nominalRate(), cmd.moratoriumRate(), cmd.openingFeeRate());
    }
}

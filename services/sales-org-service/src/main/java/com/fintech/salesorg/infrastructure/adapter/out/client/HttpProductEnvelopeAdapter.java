package com.fintech.salesorg.infrastructure.adapter.out.client;

import com.fintech.salesorg.application.port.out.ProductEnvelopePort;
import com.fintech.salesorg.domain.CatalogUnavailableException;
import com.fintech.salesorg.domain.PlacementPolicy;
import com.fintech.salesorg.domain.ProductEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The catalog's active distributor line, read when a policy is saved.
 *
 * <p>Read, never copied: the product is the catalog's to change, and a policy is checked against what the catalog
 * says that day. If the catalog cannot answer, the policy is not saved unchecked.
 */
@Component
public class HttpProductEnvelopeAdapter implements ProductEnvelopePort {

    private static final Logger log = LoggerFactory.getLogger(HttpProductEnvelopeAdapter.class);

    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST = new ParameterizedTypeReference<>() {};

    private final RestClient catalog;

    public HttpProductEnvelopeAdapter(@Qualifier("creditProductRestClient") RestClient catalog) {
        this.catalog = catalog;
    }

    @Override
    public Optional<ProductEnvelope> distributorLine() {
        List<Map<String, Object>> products;
        try {
            products = catalog.get()
                    .uri(uri -> uri.path("/api/v1/credit-products").queryParam("productType", PlacementPolicy.PRODUCT_TYPE).build())
                    .retrieve()
                    .body(LIST);
        } catch (RuntimeException e) {
            log.error("El catálogo de productos no respondió: {}", e.getMessage());
            throw new CatalogUnavailableException("No se pudo leer la línea de distribuidora del catálogo para validar la política", e);
        }
        if (products == null || products.isEmpty()) return Optional.empty();
        Map<String, Object> p = products.getFirst();
        return Optional.of(new ProductEnvelope(text(p.get("productCode")), money(p.get("minAmount")), money(p.get("maxAmount")),
                integer(p.get("minTerm")), integer(p.get("maxTerm"))));
    }

    private static String text(Object v) { return v == null ? null : String.valueOf(v); }

    private static BigDecimal money(Object v) { return v == null ? null : new BigDecimal(String.valueOf(v)); }

    private static Integer integer(Object v) { return v == null ? null : Integer.valueOf(String.valueOf(v)); }
}

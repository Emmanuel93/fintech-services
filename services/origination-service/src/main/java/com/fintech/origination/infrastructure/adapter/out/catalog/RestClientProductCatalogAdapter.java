package com.fintech.origination.infrastructure.adapter.out.catalog;

import com.fintech.origination.application.CreditProductDefinition;
import com.fintech.origination.application.port.out.ProductCatalogReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Optional;

@Component
public class RestClientProductCatalogAdapter implements ProductCatalogReader {

    private static final Logger log = LoggerFactory.getLogger(RestClientProductCatalogAdapter.class);

    private final RestClient creditProductRestClient;

    public RestClientProductCatalogAdapter(RestClient creditProductRestClient) {
        this.creditProductRestClient = creditProductRestClient;
    }

    @Override
    public Optional<CreditProductDefinition> findByCode(String productCode) {
        try {
            CreditProductDefinition def = creditProductRestClient.get()
                    .uri("/api/v1/credit-products/code/{code}", productCode)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
                        log.warn("Product not found in catalog productCode={} status={}", productCode, res.getStatusCode());
                    })
                    .body(CreditProductDefinition.class);
            return Optional.ofNullable(def);
        } catch (Exception e) {
            log.error("Catalog lookup failed productCode={}: {}", productCode, e.getMessage());
            throw new RuntimeException("Catalog lookup failed for productCode=" + productCode, e);
        }
    }
}

package com.fintech.creditproduct.infrastructure.adapter.in.api;

import com.fintech.creditproduct.application.service.CreditProductCatalogService;
import com.fintech.creditproduct.domain.*;
import com.fintech.creditproduct.infrastructure.adapter.in.api.dto.*;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/credit-products")
class CreditProductCatalogController {

    private final CreditProductCatalogService service;

    CreditProductCatalogController(CreditProductCatalogService service) {
        this.service = service;
    }

    // ── Write operations (require auth) ──────────────────────────────────────

    @PostMapping
    public ResponseEntity<CreditProductDefinitionResponse> create(
            @Valid @RequestBody CreateCreditProductRequest request) {

        var requiredDocs = request.requiredDocuments().stream()
                .map(r -> new RequiredDocument(r.documentType(), r.mandatory()))
                .collect(Collectors.toSet());

        AmortizationType amortizationType = request.amortizationType() != null
                ? AmortizationType.valueOf(request.amortizationType()) : null;

        PaymentFrequency defaultPaymentFreq = request.defaultPaymentFrequency() != null
                ? PaymentFrequency.valueOf(request.defaultPaymentFrequency()) : null;

        Set<PaymentFrequency> allowedFreqs = request.allowedPaymentFrequencies() != null
                ? request.allowedPaymentFrequencies().stream()
                        .map(PaymentFrequency::valueOf)
                        .collect(Collectors.toSet())
                : Set.of();

        List<RateCard> rateCards = toRateCards(request.rateCards());
        List<EligibilityRule> eligibilityRules = toEligibilityRules(request.eligibilityRules());

        var definition = service.create(
                request.productCode(),
                ProductType.valueOf(request.productType()),
                request.name(), request.description(),
                TargetAudience.valueOf(request.targetAudience()),
                request.currency(),
                request.nominalRateAnnual(), request.moratoriumRateAnnual(),
                request.minTerm(), request.maxTerm(), request.defaultTerm(),
                request.minAmount(), request.maxAmount(),
                request.defaultCreditLine(), request.minCreditLine(), request.maxCreditLine(),
                request.amountStep(), request.termStep(),
                amortizationType, defaultPaymentFreq, allowedFreqs,
                request.minApprovalScore(),
                ApprovalFlow.valueOf(request.defaultApprovalFlow()),
                request.openingFeeRate(), request.prepaymentFeeRate(),
                request.eligiblePartyTypes(), requiredDocs, request.channelAvailabilities(),
                request.capabilities(), rateCards, eligibilityRules
        );

        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(definition.getProductDefinitionId()).toUri();

        return ResponseEntity.created(location).body(CreditProductDefinitionResponse.from(definition));
    }

    @PutMapping("/{id}/activate")
    public ResponseEntity<CreditProductDefinitionResponse> activate(@PathVariable UUID id) {
        return ResponseEntity.ok(CreditProductDefinitionResponse.from(service.activate(id)));
    }

    @PutMapping("/{id}/deactivate")
    public ResponseEntity<CreditProductDefinitionResponse> deactivate(@PathVariable UUID id) {
        return ResponseEntity.ok(CreditProductDefinitionResponse.from(service.deactivate(id)));
    }

    @PutMapping("/{id}/reactivate")
    public ResponseEntity<CreditProductDefinitionResponse> reactivate(@PathVariable UUID id) {
        return ResponseEntity.ok(CreditProductDefinitionResponse.from(service.reactivate(id)));
    }

    @PutMapping("/{id}/deprecate")
    public ResponseEntity<CreditProductDefinitionResponse> deprecate(@PathVariable UUID id) {
        return ResponseEntity.ok(CreditProductDefinitionResponse.from(service.deprecate(id)));
    }

    /** Retires the currently ACTIVE version of a productCode (superseded by a new version). */
    @PutMapping("/code/{code}/retire")
    public ResponseEntity<CreditProductDefinitionResponse> retireByCode(@PathVariable String code) {
        return ResponseEntity.ok(CreditProductDefinitionResponse.from(service.retire(code)));
    }

    // ── Read operations (public) ──────────────────────────────────────────────

    /** Returns all ACTIVE product definitions, filterable by productType and targetAudience. */
    @GetMapping
    public ResponseEntity<List<CreditProductDefinitionResponse>> findActive(
            @RequestParam(required = false) String productType,
            @RequestParam(required = false) String targetAudience) {

        ProductType pt  = productType     != null ? ProductType.valueOf(productType)       : null;
        TargetAudience ta = targetAudience != null ? TargetAudience.valueOf(targetAudience) : null;

        return ResponseEntity.ok(
                service.findActive(pt, ta).stream()
                        .map(CreditProductDefinitionResponse::from)
                        .toList());
    }

    /** Returns ALL product definitions, any status — para la administración del catálogo (backoffice). */
    @GetMapping("/all")
    public ResponseEntity<List<CreditProductDefinitionResponse>> findAll() {
        return ResponseEntity.ok(
                service.findAll().stream()
                        .map(CreditProductDefinitionResponse::from)
                        .toList());
    }

    /** Returns a product definition by UUID (any status). */
    @GetMapping("/{id}")
    public ResponseEntity<CreditProductDefinitionResponse> findById(@PathVariable UUID id) {
        return service.findById(id)
                .map(d -> CreditProductDefinitionResponse.from(d,
                        service.findRateCards(d.getProductDefinitionId()),
                        service.findEligibilityRules(d.getProductDefinitionId())))
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /** Returns the currently ACTIVE version of a productCode. */
    @GetMapping("/code/{code}")
    public ResponseEntity<CreditProductDefinitionResponse> findByCode(@PathVariable String code) {
        return service.findByProductCode(code)
                .map(d -> CreditProductDefinitionResponse.from(d,
                        service.findRateCards(d.getProductDefinitionId()),
                        service.findEligibilityRules(d.getProductDefinitionId())))
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /** Returns all versions of a productCode ordered by version desc. */
    @GetMapping("/code/{code}/versions")
    public ResponseEntity<List<CreditProductDefinitionResponse>> findVersionHistory(
            @PathVariable String code) {
        List<CreditProductDefinitionResponse> history = service.findVersionHistory(code).stream()
                .map(CreditProductDefinitionResponse::from)
                .toList();
        return ResponseEntity.ok(history);
    }

    /** Returns rate cards for a specific product definition. */
    @GetMapping("/{id}/rate-cards")
    public ResponseEntity<List<RateCardResponse>> findRateCards(@PathVariable UUID id) {
        return ResponseEntity.ok(
                service.findRateCards(id).stream()
                        .map(RateCardResponse::from)
                        .toList());
    }

    /** Returns eligibility rules for a specific product definition. */
    @GetMapping("/{id}/eligibility-rules")
    public ResponseEntity<List<EligibilityRuleResponse>> findEligibilityRules(@PathVariable UUID id) {
        return ResponseEntity.ok(
                service.findEligibilityRules(id).stream()
                        .map(EligibilityRuleResponse::from)
                        .toList());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private List<RateCard> toRateCards(List<RateCardRequest> requests) {
        if (requests == null || requests.isEmpty()) return List.of();
        return requests.stream()
                .map(r -> RateCard.create(null, r.tierBand(),
                        r.minAmount(), r.maxAmount(),
                        r.minTerm(), r.maxTerm(),
                        r.nominalRate(), r.moratoriumRate()))
                .toList();
    }

    private List<EligibilityRule> toEligibilityRules(List<EligibilityRuleRequest> requests) {
        if (requests == null || requests.isEmpty()) return List.of();
        return requests.stream()
                .map(r -> {
                    EligibilityRuleType type = EligibilityRuleType.valueOf(r.ruleType());
                    if (type == EligibilityRuleType.REQUIRED_PARTY_TYPE) {
                        return EligibilityRule.partyType(null, r.stringValue(), r.errorCode());
                    }
                    return EligibilityRule.numeric(null, type,
                            EligibilityOperator.valueOf(r.operator()),
                            r.thresholdValue(), r.errorCode());
                })
                .toList();
    }

    /**
     * Re-publica la configuración de un producto activo, sin cambiarlo.
     *
     * <p>Para cuando el catálogo se corrigió por fuera de esta API —un changeset de seed, un ajuste
     * directo— y los consumidores conservan la copia con la que se activó. Sin esto, un cambio de
     * configuración no llega nunca y el síntoma aparece lejos: el producto dice una cosa y cartera
     * se comporta según otra.
     */
    // La autorización va en `SecurityConfig` como el resto del controlador: todo POST de este
    // catálogo exige ROLE_ADMIN, y anotarlo aquí además sería una segunda fuente de la misma regla.
    @PostMapping("/{productCode}/republish")
    public ResponseEntity<Void> republish(@PathVariable String productCode) {
        service.republishConfig(productCode);
        return ResponseEntity.accepted().build();
    }
}

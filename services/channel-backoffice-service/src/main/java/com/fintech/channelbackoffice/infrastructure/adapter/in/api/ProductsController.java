package com.fintech.channelbackoffice.infrastructure.adapter.in.api;

import com.fintech.channelbackoffice.infrastructure.adapter.out.client.CreditProductClient;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.ScoringClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Catálogo de productos para el backoffice.
 *
 * <p>El modelo del dominio es más rico que el formulario de la consola: al crear,
 * el BFF completa con <b>defaults sensatos</b> los campos que el form no captura
 * (moneda MXN, tipos de party elegibles según la audiencia, documentos y canales
 * mínimos). El admin los refina luego con un formulario completo. "Retirar" una
 * versión concreta se mapea a <em>deprecar</em> esa versión en el dominio.
 */
@RestController
@Tag(name = "Productos", description = "Catálogo de productos de crédito")
class ProductsController {

    private static final Logger log = LoggerFactory.getLogger(ProductsController.class);

    private final CreditProductClient creditProductClient;
    private final ScoringClient scoringClient;

    ProductsController(CreditProductClient creditProductClient, ScoringClient scoringClient) {
        this.creditProductClient = creditProductClient;
        this.scoringClient = scoringClient;
    }

    @GetMapping("/products")
    @Operation(summary = "Catálogo completo (todas las versiones y estados)")
    List<Map<String, Object>> list() {
        log.info("GET /products");
        return creditProductClient.findAll().stream().map(BackofficeViews::product).toList();
    }

    @GetMapping("/products/{id}")
    @Operation(summary = "Detalle de un producto: sus condiciones y el scoring asignado",
            description = "Para el modal del catálogo. Compone las condiciones del producto con la(s) "
                    + "política(s) de scoring que aplican a su tipo — reglas y umbrales en lenguaje claro.")
    Map<String, Object> detail(@PathVariable UUID id) {
        log.info("GET /products/{}", id);
        var product = creditProductClient.getById(id);
        // El catálogo de políticas es pequeño; se piden todas y se filtran por el tipo del producto.
        var policies = scoringClient.listPolicies();
        return BackofficeViews.productDetail(product, policies);
    }

    @PostMapping("/products")
    @Operation(summary = "Crear una versión de producto",
            description = "El BFF completa los campos que el form no captura con defaults editables luego.")
    Map<String, Object> create(@RequestBody CreateProductRequest req) {
        log.info("POST /products code={} type={}", req.productCode(), req.productType());
        return BackofficeViews.product(creditProductClient.create(toDomainCreate(req)));
    }

    // ── Política de riesgo ────────────────────────────────────────────────────

    @GetMapping("/scoring/rule-types")
    @Operation(summary = "Variables del buró que se pueden configurar en una política",
            description = "Catálogo con metadatos —qué mide cada variable, en qué unidad, si acepta "
                        + "tipo de crédito o ventana de meses— para que el alta de políticas se "
                        + "construya desde aquí y no desde una lista escrita en la consola.")
    List<Map<String, Object>> ruleTypes() {
        log.info("GET /scoring/rule-types");
        return scoringClient.ruleTypes();
    }

    @GetMapping("/scoring/policies")
    @Operation(summary = "Políticas de scoring activas")
    List<Map<String, Object>> policies() {
        log.info("GET /scoring/policies");
        return scoringClient.listPolicies().stream().map(BackofficeViews::policy).toList();
    }

    @PostMapping("/scoring/policies")
    @Operation(summary = "Dar de alta una política de riesgo",
            description = "Reglas —qué variable, con qué operador, contra qué umbral y cuántos puntos— "
                        + "y la matriz de riesgo que traduce el score final en aprobación automática, "
                        + "revisión manual o rechazo. Exige products.manage: configurar cómo se "
                        + "autoriza el crédito es la misma facultad que definir el producto.")
    Map<String, Object> createPolicy(@RequestBody Map<String, Object> body) {
        log.info("POST /scoring/policies type={}", body.get("productTypeIntent"));
        return scoringClient.createPolicy(body);
    }

    @PutMapping("/products/{id}/activate")
    @Operation(summary = "Activar una versión (retira la versión ACTIVE previa del mismo código)")
    Map<String, Object> activate(@PathVariable UUID id) {
        return BackofficeViews.product(creditProductClient.activate(id));
    }

    @PutMapping("/products/{id}/retire")
    @Operation(summary = "Retirar (deprecar) una versión de producto")
    Map<String, Object> retire(@PathVariable UUID id) {
        return BackofficeViews.product(creditProductClient.deprecate(id));
    }

    // ── Mapeo FE → dominio con defaults ───────────────────────────────────────

    private static Map<String, Object> toDomainCreate(CreateProductRequest r) {
        boolean revolving = "REVOLVING".equalsIgnoreCase(r.behavior());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("productCode", r.productCode());
        body.put("productType", r.productType());
        body.put("name", r.name());
        body.put("description", r.description());
        body.put("targetAudience", r.targetAudience());
        body.put("currency", "MXN");                                    // default
        body.put("nominalRateAnnual", r.nominalRateAnnual());
        body.put("moratoriumRateAnnual", r.moratoriumRateAnnual());
        body.put("minTerm", r.minTerm());
        body.put("maxTerm", r.maxTerm());
        body.put("defaultTerm", r.defaultTerm());
        body.put("minAmount", r.minAmount());
        body.put("maxAmount", r.maxAmount());
        body.put("defaultCreditLine", r.defaultCreditLine());
        body.put("minCreditLine", r.minCreditLine());
        body.put("maxCreditLine", r.maxCreditLine());
        body.put("amountStep", 1000);                                   // default
        body.put("amortizationType", revolving ? null : r.amortizationType());
        body.put("defaultPaymentFrequency", revolving ? null : "MONTHLY");     // default
        body.put("allowedPaymentFrequencies", revolving ? Set.of() : Set.of("MONTHLY"));
        body.put("minApprovalScore", r.minApprovalScore());
        body.put("defaultApprovalFlow", r.defaultApprovalFlow());
        body.put("openingFeeRate", r.openingFeeRate());
        body.put("prepaymentFeeRate", r.prepaymentFeeRate());
        body.put("eligiblePartyTypes", eligiblePartyTypes(r.targetAudience()));  // default por audiencia
        body.put("requiredDocuments", List.of(Map.of("documentType", "INCOME_PROOF", "mandatory", true)));
        body.put("channelAvailabilities", Set.of("WEB"));               // default
        body.put("capabilities", null);
        body.put("rateCards", List.of());
        body.put("eligibilityRules", List.of());
        return body;
    }

    /** Default de tipos de party según la audiencia comercial (editable luego). */
    private static Set<String> eligiblePartyTypes(String targetAudience) {
        if (targetAudience == null) return Set.of("INDIVIDUAL");
        return switch (targetAudience) {
            case "B2B", "B2B2C" -> Set.of("BUSINESS");
            default -> Set.of("INDIVIDUAL");
        };
    }

    /** Lo que captura el formulario del backoffice (shared-types CreateProductVersionRequest). */
    record CreateProductRequest(
            String productCode, String productType, String behavior, String name, String description,
            String targetAudience, BigDecimal nominalRateAnnual, BigDecimal moratoriumRateAnnual,
            Integer minTerm, Integer maxTerm, Integer defaultTerm,
            BigDecimal minAmount, BigDecimal maxAmount,
            BigDecimal defaultCreditLine, BigDecimal minCreditLine, BigDecimal maxCreditLine,
            String amortizationType, Integer minApprovalScore, String defaultApprovalFlow,
            BigDecimal openingFeeRate, BigDecimal prepaymentFeeRate) {}
}

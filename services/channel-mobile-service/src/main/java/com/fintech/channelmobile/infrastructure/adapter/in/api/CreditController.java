package com.fintech.channelmobile.infrastructure.adapter.in.api;

import com.fintech.channelmobile.infrastructure.adapter.in.api.dto.CreateApplicationRequest;
import com.fintech.channelmobile.infrastructure.adapter.in.api.dto.AmountRequest;
import com.fintech.channelmobile.infrastructure.adapter.in.api.dto.ContractSignRequest;
import com.fintech.channelmobile.infrastructure.adapter.in.api.dto.DisposeRequest;
import com.fintech.channelmobile.infrastructure.adapter.out.client.CreditPortfolioClient;
import com.fintech.channelmobile.infrastructure.adapter.out.client.CreditProductClient;
import com.fintech.channelmobile.infrastructure.adapter.out.client.OriginationClient;
import com.fintech.channelmobile.infrastructure.adapter.out.client.OriginationClient.CreateApplicationPayload;
import com.fintech.channelmobile.infrastructure.adapter.out.client.PaymentsClient;
import com.fintech.channelmobile.infrastructure.adapter.out.client.ScoringClient;
import com.fintech.channelmobile.infrastructure.adapter.out.client.WalletClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Flujo de crédito separado del alta de prospecto (ADR-001): el catálogo de
 * productos y la creación de CreditApplication. Requiere sesión (JWT de identity).
 */
@RestController
@Tag(name = "Credit", description = "Catálogo de productos y aplicaciones de crédito")
@SecurityRequirement(name = "bearerAuth")
class CreditController {

    private static final Logger log = LoggerFactory.getLogger(CreditController.class);

    private final CreditProductClient creditProductClient;
    private final OriginationClient originationClient;
    private final CreditPortfolioClient creditPortfolioClient;
    private final WalletClient walletClient;
    private final PaymentsClient paymentsClient;
    private final ScoringClient scoringClient;

    CreditController(CreditProductClient creditProductClient,
                     OriginationClient originationClient,
                     CreditPortfolioClient creditPortfolioClient,
                     WalletClient walletClient,
                     PaymentsClient paymentsClient,
                     ScoringClient scoringClient) {
        this.creditProductClient   = creditProductClient;
        this.originationClient     = originationClient;
        this.creditPortfolioClient = creditPortfolioClient;
        this.walletClient          = walletClient;
        this.paymentsClient        = paymentsClient;
        this.scoringClient         = scoringClient;
    }

    @Operation(summary = "Listar productos de crédito activos del catálogo")
    @ApiResponse(responseCode = "200", description = "Catálogo de productos activos")
    @GetMapping("/credit/products")
    ResponseEntity<Map<String, Object>> products() {
        return ResponseEntity.ok(Map.of("products", creditProductClient.listActiveProducts()));
    }

    @Operation(summary = "Precalificación B2C para el home (\"créditos disponibles\")",
            description = "Evalúa al prospecto contra todos los productos B2C disponibles en MOBILE_APP de "
                        + "una sola vez y devuelve el catálogo mergeado con la decisión de cada uno. Cacheado "
                        + "server-side (ver scoring-service) — seguro de llamar en cada carga del home.")
    @ApiResponse(responseCode = "200", description = "Catálogo B2C mergeado con resultado de precalificación")
    @GetMapping("/credit/prequalification")
    ResponseEntity<Map<String, Object>> prequalification(HttpServletRequest httpRequest) {
        // X-User-Id (JWT sub) — no depende de que el cliente haya pasado por el
        // flujo de registro en esta misma sesión de la app (AppSession se pierde
        // en un login normal contra una cuenta ya existente); mismo patrón ya
        // usado por getAccount()/makePayment()/dispose() en este controller.
        String prospectId = httpRequest.getHeader("X-User-Id");
        List<Map<String, Object>> mobileB2cProducts = creditProductClient.listActiveProducts("B2C").stream()
                .filter(CreditController::isMobileAvailable)
                .toList();
        List<String> productTypes = mobileB2cProducts.stream()
                .map(p -> String.valueOf(p.get("productType")))
                .distinct()
                .toList();

        ScoringClient.PrequalificationResponse prequal =
                scoringClient.prequalify(UUID.fromString(prospectId), "INDIVIDUAL", productTypes);
        Map<String, ScoringClient.PrequalificationItem> byProductType = prequal.results().stream()
                .collect(java.util.stream.Collectors.toMap(
                        ScoringClient.PrequalificationItem::productType, i -> i, (a, b) -> a));

        List<Map<String, Object>> merged = mobileB2cProducts.stream()
                .map(p -> {
                    Map<String, Object> withDecision = new java.util.HashMap<>(p);
                    ScoringClient.PrequalificationItem item = byProductType.get(String.valueOf(p.get("productType")));
                    withDecision.put("evaluated", item != null && item.evaluated());
                    withDecision.put("decision", item != null ? item.decision() : null);
                    withDecision.put("riskLevel", item != null ? item.riskLevel() : null);
                    withDecision.put("skippedReason", item != null ? item.skippedReason() : "NOT_EVALUATED");
                    return withDecision;
                })
                .toList();
        return ResponseEntity.ok(Map.of("products", merged));
    }

    private static boolean isMobileAvailable(Map<String, Object> product) {
        Object channels = product.get("channelAvailabilities");
        return channels instanceof java.util.Collection<?> c && c.contains("MOBILE_APP");
    }

    @Operation(summary = "Crear una aplicación de crédito (elegir producto → dispara scoring)")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Aplicación creada — estado PENDING_SCORING"),
        @ApiResponse(responseCode = "404", description = "Prospecto no encontrado"),
        @ApiResponse(responseCode = "409", description = "Ya existe una aplicación activa para ese producto")
    })
    @PostMapping("/credit/applications")
    ResponseEntity<Map<String, Object>> createApplication(
            @Valid @RequestBody CreateApplicationRequest request,
            HttpServletRequest httpRequest) {
        String userId = httpRequest.getHeader("X-User-Id");
        // El JWT (X-User-Id) es autoritativo — se prefiere sobre lo que mande
        // el cliente. request.prospectId() solo se usa si por algún motivo no
        // hay header (no debería pasar, el gateway ya exige JWT válido).
        String prospectId = (userId != null && !userId.isBlank()) ? userId : request.prospectId();
        log.info("Create credit application prospectId={} productType={} promoterCode={} userId={}",
                prospectId, request.productType(), request.promoterCode(), userId);
        Map<String, Object> created = originationClient.createApplication(userId,
                new CreateApplicationPayload(
                        prospectId,
                        request.productType(),
                        request.requestedAmount(),
                        request.requestedTerm(),
                        request.promoterCode()));
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @Operation(summary = "Consultar el estado de una aplicación (polling de la decisión de scoring)")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Detalle de la aplicación"),
        @ApiResponse(responseCode = "404", description = "Aplicación no encontrada")
    })
    @GetMapping("/credit/applications/{id}")
    ResponseEntity<Map<String, Object>> getApplication(@PathVariable String id,
                                                       HttpServletRequest httpRequest) {
        return ResponseEntity.ok(
                originationClient.getApplication(httpRequest.getHeader("X-User-Id"), id));
    }

    @Operation(summary = "Listar las aplicaciones de un prospecto")
    @ApiResponse(responseCode = "200", description = "Aplicaciones del prospecto")
    @GetMapping("/credit/applications")
    ResponseEntity<Map<String, Object>> listApplications(
            @RequestParam(required = false) String prospectId,
            HttpServletRequest httpRequest) {
        // El prospecto sale del token, como en el resto del controller. Se
        // acepta por query sólo por compatibilidad con clientes que ya lo
        // mandan: exigirlo permitiría pedir las solicitudes de otra persona
        // con sólo cambiar el parámetro.
        String userId = httpRequest.getHeader("X-User-Id");
        return ResponseEntity.ok(Map.of("applications",
                originationClient.listApplications(userId,
                        prospectId != null ? prospectId : userId)));
    }

    // ── Oferta y contrato ────────────────────────────────────────────────────
    // El BFF orquesta el tramo APPROVED → OFFER_PRESENTED → OFFER_ACCEPTED →
    // PENDING_SIGNATURE → CONTRACT_SIGNED. Firmar dispara la activación de la cuenta
    // y el WalletView (vía Kafka) sin más intervención.

    @Operation(summary = "Presentar la oferta de una solicitud aprobada",
            description = "La solicitud debe estar APPROVED. El BFF resuelve el productCode del catálogo "
                        + "a partir del productType de la solicitud y calcula la oferta (línea/tasa/CAT).")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Oferta presentada (OFFER_PRESENTED)"),
        @ApiResponse(responseCode = "404", description = "Solicitud o producto no encontrado")
    })
    @PostMapping("/credit/applications/{id}/offer")
    ResponseEntity<Map<String, Object>> presentOffer(@PathVariable String id, HttpServletRequest httpRequest) {
        String userId = httpRequest.getHeader("X-User-Id");
        Map<String, Object> app = originationClient.getApplication(userId, id);
        String productType = (String) app.get("productType");
        String productCode = resolveProductCode(productType);
        Double offeredAmount = app.get("requestedAmount") instanceof Number n ? n.doubleValue() : null;
        Integer offeredTerm = app.get("requestedTerm") instanceof Number n ? n.intValue() : null;
        return ResponseEntity.ok(originationClient.presentOffer(userId, id, productCode, offeredAmount, offeredTerm));
    }

    @Operation(summary = "Aceptar la oferta")
    @PostMapping("/credit/applications/{id}/offer/accept")
    ResponseEntity<Map<String, Object>> acceptOffer(@PathVariable String id, HttpServletRequest httpRequest) {
        return ResponseEntity.ok(originationClient.acceptOffer(httpRequest.getHeader("X-User-Id"), id));
    }

    @Operation(summary = "Rechazar la oferta")
    @PostMapping("/credit/applications/{id}/offer/reject")
    ResponseEntity<Map<String, Object>> rejectOffer(@PathVariable String id, HttpServletRequest httpRequest) {
        return ResponseEntity.ok(originationClient.rejectOffer(httpRequest.getHeader("X-User-Id"), id));
    }

    @Operation(summary = "Generar el contrato (pasa a PENDING_SIGNATURE)",
            description = "La solicitud debe estar OFFER_ACCEPTED. Método de firma por default: OTP.")
    @PostMapping("/credit/applications/{id}/contract")
    ResponseEntity<Map<String, Object>> generateContract(@PathVariable String id, HttpServletRequest httpRequest) {
        return ResponseEntity.ok(
                originationClient.generateContract(httpRequest.getHeader("X-User-Id"), id, "OTP"));
    }

    @Operation(summary = "Firmar el contrato (CONTRACT_SIGNED → activa la cuenta)",
            description = "La solicitud debe estar PENDING_SIGNATURE. Al firmar, origination emite "
                        + "CreditProductCreationRequested → credit-portfolio crea y activa la cuenta → "
                        + "wallet crea el WalletView (todo async por Kafka).")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Contrato firmado (CONTRACT_SIGNED)"),
        @ApiResponse(responseCode = "400", description = "CLABE inválida, firma inválida o estado incorrecto")
    })
    @PostMapping("/credit/applications/{id}/contract/sign")
    ResponseEntity<Map<String, Object>> signContract(@PathVariable String id,
                                                     @Valid @RequestBody ContractSignRequest request,
                                                     HttpServletRequest httpRequest) {
        return ResponseEntity.ok(originationClient.signContract(
                httpRequest.getHeader("X-User-Id"), id,
                request.clabeAccount(), request.signatureProof(), request.documentRef()));
    }

    private String resolveProductCode(String productType) {
        return creditProductClient.listActiveProducts().stream()
                .filter(p -> productType != null && productType.equalsIgnoreCase(String.valueOf(p.get("productType"))))
                .map(p -> String.valueOf(p.get("productCode")))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No hay producto activo en el catálogo para el tipo " + productType));
    }

    @Operation(summary = "Obtener cuenta(s) de crédito activa(s) del usuario autenticado")
    @ApiResponse(responseCode = "200", description = "Cuentas de crédito del party")
    @GetMapping("/credit/account")
    ResponseEntity<List<CreditPortfolioClient.CreditAccountResponse>> getAccount(HttpServletRequest httpRequest) {
        String userId = httpRequest.getHeader("X-User-Id");
        UUID partyId = UUID.fromString(userId);
        log.info("GET /credit/account partyId={}", partyId);
        return ResponseEntity.ok(creditPortfolioClient.getAccountsByPartyId(partyId, userId));
    }

    @Operation(summary = "Pagar el crédito",
            description = "Envía el pago a payments-service (pre-valida contra su balance snapshot y "
                        + "publica payment-applied). La actualización de saldo en credit-portfolio y la "
                        + "confirmación del pago ocurren async vía Kafka.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Pago enviado (PENDING o CONFIRMED)"),
        @ApiResponse(responseCode = "404", description = "El usuario no tiene cuenta de crédito activa"),
        @ApiResponse(responseCode = "422", description = "Cuenta no activa o sin balance snapshot todavía")
    })
    @PostMapping("/credit/payment")
    ResponseEntity<Map<String, Object>> makePayment(@Valid @RequestBody AmountRequest request,
                                                     HttpServletRequest httpRequest) {
        String userId = httpRequest.getHeader("X-User-Id");
        UUID obligorPartyId = UUID.fromString(userId);
        UUID creditAccountId = resolvePrimaryCreditAccountId(userId);
        log.info("POST /credit/payment creditAccountId={} amount={}", creditAccountId, request.amount());
        PaymentsClient.PaymentOrderResponse order = paymentsClient.submit(
                creditAccountId, obligorPartyId, userId, BigDecimal.valueOf(request.amount()), "SPEI");
        return ResponseEntity.ok(Map.of(
                "success", true,
                "paymentOrderId", order.paymentOrderId().toString(),
                "status", order.status()));
    }

    @Operation(summary = "Disponer de la línea de crédito",
            description = "Solicita una disposición (SELF_USE) en wallet-service — 202 aceptado, "
                        + "el dinero se libera de forma async hacia walletBalance vía Kafka.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Disposición solicitada"),
        @ApiResponse(responseCode = "404", description = "El usuario no tiene cuenta de crédito activa"),
        @ApiResponse(responseCode = "422", description = "Crédito insuficiente o cuenta suspendida")
    })
    @PostMapping("/credit/dispose")
    ResponseEntity<Map<String, Object>> dispose(@Valid @RequestBody DisposeRequest request,
                                                 HttpServletRequest httpRequest) {
        String userId = httpRequest.getHeader("X-User-Id");
        UUID creditAccountId = resolvePrimaryCreditAccountId(userId);
        String dispositionType = (request.dispositionType() == null || request.dispositionType().isBlank())
                ? "SELF_USE" : request.dispositionType();
        UUID beneficiary = (request.beneficiaryPartyId() == null || request.beneficiaryPartyId().isBlank())
                ? null : UUID.fromString(request.beneficiaryPartyId());
        log.info("POST /credit/dispose creditAccountId={} amount={} type={}",
                creditAccountId, request.amount(), dispositionType);
        walletClient.requestDisposition(creditAccountId, userId,
                BigDecimal.valueOf(request.amount()), dispositionType, beneficiary,
                request.termPeriods());
        return ResponseEntity.ok(Map.of("success", true));
    }

    private UUID resolvePrimaryCreditAccountId(String userId) {
        UUID partyId = UUID.fromString(userId);
        List<CreditPortfolioClient.CreditAccountResponse> accounts =
                creditPortfolioClient.getAccountsByPartyId(partyId, userId);
        if (accounts.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "El usuario no tiene cuentas de crédito activas");
        }
        return accounts.get(0).creditAccountId();
    }
}

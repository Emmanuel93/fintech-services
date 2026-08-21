package com.fintech.channelbackoffice.infrastructure.adapter.in.api;

import com.fintech.channelbackoffice.infrastructure.adapter.out.client.CollectionsClient;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.CollectionsClient.CollectionCaseResponse;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.CreditPortfolioClient;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.PartyClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Cobranza para el backoffice.
 *
 * <p><b>El prefijo va en plural y sin versionar</b> — {@code /collections}, no {@code /collection}
 * ni {@code /api/v1/…}—, igual que el resto de los controladores del canal. No es estética: el
 * matcher de seguridad declarado es {@code /collections/**} y exige la capacidad de cartera. Una
 * ruta escrita en singular no casa con él, cae en el {@code anyRequest().authenticated()} final y
 * responde igual de bien… abierta a cualquier empleado con sesión. No se nota probando la pantalla;
 * se nota en una auditoría.
 *
 * <p><b>Los nombres de los obligados se resuelven en lote.</b> Un caso trae {@code obligorPartyId}
 * y nada más; pedirle el nombre a party fila por fila convierte una bandeja de 25 en 26 llamadas.
 *
 * <p><b>El saldo que se muestra es el de cartera, no el del caso.</b> {@code totalDebt} del caso es
 * una proyección local alimentada por eventos y va un instante por detrás. Pintar los dos lado a
 * lado sólo produce la pregunta de cuál es el bueno; el del caso queda para el tope de condonación,
 * que es contra el que valida el dominio.
 */
@RestController
@Tag(name = "Cobranza", description = "Casos de mora, gestión, convenios y quebranto")
class CollectionsController {

    private static final Logger log = LoggerFactory.getLogger(CollectionsController.class);

    private final CollectionsClient collectionsClient;
    private final PartyClient partyClient;
    private final CreditPortfolioClient creditPortfolioClient;

    CollectionsController(CollectionsClient collectionsClient, PartyClient partyClient,
                          CreditPortfolioClient creditPortfolioClient) {
        this.collectionsClient     = collectionsClient;
        this.partyClient           = partyClient;
        this.creditPortfolioClient = creditPortfolioClient;
    }

    // ── Bandeja ──────────────────────────────────────────────────────────────

    @GetMapping("/collections/cases")
    @Operation(summary = "Bandeja de cobranza paginada",
            description = "Filtros opcionales por estado, bucket, producto, gestor y días de mora.")
    ResponseEntity<Map<String, Object>> listCases(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String bucket,
            @RequestParam(required = false) String productType,
            @RequestParam(required = false) String assignedAgentId,
            @RequestParam(required = false) Integer minDaysDelinquent,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "daysDelinquent,desc") String sort) {

        var result = collectionsClient.searchCases(
                status, bucket, productType, assignedAgentId, minDaysDelinquent, page, size, sort);
        List<CollectionCaseResponse> rows = result == null || result.content() == null
                ? List.of() : result.content();

        Map<UUID, String> names = resolveNames(rows);

        List<Map<String, Object>> content = rows.stream()
                .map(c -> caseView(c, names.get(c.obligorPartyId())))
                .toList();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("content", content);
        body.put("page", result == null ? page : result.number());
        body.put("size", result == null ? size : result.size());
        body.put("totalElements", result == null ? 0 : result.totalElements());
        body.put("totalPages", result == null ? 0 : result.totalPages());
        return ResponseEntity.ok(body);
    }

    // ── Detalle ──────────────────────────────────────────────────────────────

    @GetMapping("/collections/cases/{caseId}")
    @Operation(summary = "Detalle de un caso con su gestión acumulada")
    ResponseEntity<Map<String, Object>> caseDetail(@PathVariable UUID caseId) {
        log.info("GET /collections/cases/{}", caseId);
        return ResponseEntity.ok(hydrate(collectionsClient.getCase(caseId)));
    }

    @GetMapping("/collections/accounts/{creditAccountId}/case")
    @Operation(summary = "Caso activo de una cuenta de crédito")
    ResponseEntity<Map<String, Object>> caseByAccount(@PathVariable UUID creditAccountId) {
        log.info("GET /collections/accounts/{}/case", creditAccountId);
        return ResponseEntity.ok(hydrate(collectionsClient.getCaseByAccount(creditAccountId)));
    }

    /**
     * El caso con todo lo que la ficha necesita: nombre del obligado, saldos reales de cartera y la
     * gestión acumulada. Cada pieza secundaria se degrada sola —si party o cartera no contestan, la
     * ficha pierde el nombre o los saldos pero conserva el caso—. Una pantalla incompleta sirve;
     * una en blanco por un servicio secundario, no.
     */
    private Map<String, Object> hydrate(CollectionCaseResponse c) {
        if (c == null) return Map.of();

        Map<String, Object> body = new LinkedHashMap<>(caseView(c, nameOf(c.obligorPartyId())));

        try {
            var account = creditPortfolioClient.getById(c.creditAccountId());
            if (account != null) {
                Map<String, Object> cartera = new LinkedHashMap<>();
                cartera.put("contractNumber", account.contractNumber());
                cartera.put("productType", account.productType());
                cartera.put("status", account.status());
                cartera.put("principalBalance", account.principalBalance());
                // El mismo cálculo que la ficha de cartera: si cobranza sumara por su cuenta, las
                // dos pantallas podrían discrepar en el mismo saldo.
                cartera.put("totalDebt", BackofficeViews.totalDebt(account));
                cartera.put("daysDelinquent", account.daysDelinquent());
                cartera.put("nominalRate", account.nominalRate());
                body.put("account", cartera);
            }
        } catch (Exception ex) {
            log.warn("Sin datos de cartera para el caso {}: {}", c.caseId(), ex.getMessage());
        }

        try {
            var contacts = collectionsClient.contactAttempts(c.caseId());
            if (contacts != null) {
                body.put("contactAttempts", contacts.attempts());
                body.put("contactAttemptsToday", contacts.todayCount());
                body.put("maxContactAttemptsPerDay", contacts.maxPerDay());
            }
        } catch (Exception ex) {
            log.warn("Sin contactos para el caso {}: {}", c.caseId(), ex.getMessage());
        }

        try { body.put("paymentPromises", collectionsClient.paymentPromises(c.caseId())); }
        catch (Exception ex) { log.warn("Sin promesas para el caso {}: {}", c.caseId(), ex.getMessage()); }

        // Por qué está callada la cobranza automática. Va en la ficha porque un gestor que no ve el
        // freno concluye que el sistema dejó de trabajar el caso, y lo empieza a llamar encima de
        // una promesa que él mismo concedió.
        try { body.put("communicationHolds", collectionsClient.communicationHolds(c.caseId())); }
        catch (Exception ex) { log.warn("Sin frenos para el caso {}: {}", c.caseId(), ex.getMessage()); }

        try { body.put("agreements", collectionsClient.agreements(c.caseId())); }
        catch (Exception ex) { log.warn("Sin convenios para el caso {}: {}", c.caseId(), ex.getMessage()); }

        // El quebranto existe sólo si la cuenta se quebrantó; un 404 aquí es la respuesta normal,
        // no un fallo, así que no se registra como advertencia.
        try { body.put("writeOff", collectionsClient.writeOffByAccount(c.creditAccountId())); }
        catch (Exception ignored) { body.put("writeOff", null); }

        return body;
    }

    // ── Gestión ──────────────────────────────────────────────────────────────

    @PostMapping("/collections/cases/{caseId}/contact-attempts")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Registrar un intento de contacto (ventana CONDUSEF y tope diario)")
    Object recordContact(@PathVariable UUID caseId, @RequestBody Map<String, Object> body) {
        return collectionsClient.recordContactAttempt(caseId, body);
    }

    @PostMapping("/collections/cases/{caseId}/payment-promises")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Registrar una promesa de pago — una activa por caso")
    Object createPromise(@PathVariable UUID caseId, @RequestBody Map<String, Object> body) {
        return collectionsClient.createPaymentPromise(caseId, body);
    }

    // ── Convenios ────────────────────────────────────────────────────────────

    @GetMapping("/collections/agreements/awaiting-authorization")
    @Operation(summary = "Convenios aceptados por el deudor, pendientes de autorizar")
    Object awaitingAuthorization() {
        return collectionsClient.agreementsAwaitingAuthorization();
    }

    @PostMapping("/collections/cases/{caseId}/agreements")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Proponer un convenio — RESTRUCTURE o QUITA_PARCIAL")
    Object proposeAgreement(@PathVariable UUID caseId, @RequestBody Map<String, Object> body) {
        return collectionsClient.proposeAgreement(caseId, body);
    }

    @PutMapping("/collections/agreements/{agreementId}/accept")
    @Operation(summary = "El deudor acepta el convenio propuesto")
    Object acceptAgreement(@PathVariable UUID agreementId) {
        return collectionsClient.acceptAgreement(agreementId);
    }

    @PutMapping("/collections/agreements/{agreementId}/reject")
    @Operation(summary = "El deudor rechaza el convenio propuesto")
    Object rejectAgreement(@PathVariable UUID agreementId) {
        return collectionsClient.rejectAgreement(agreementId);
    }

    @PutMapping("/collections/agreements/{agreementId}/authorize")
    @Operation(summary = "Autorizar y ejecutar un convenio aceptado (AG-02)",
            description = "Autoriza y ejecuta en un paso: no hay estado intermedio. El saldo lo "
                        + "aplica credit-portfolio al recibir el evento, no aquí.")
    Object authorizeAgreement(@PathVariable UUID agreementId, @RequestBody Map<String, Object> body) {
        return collectionsClient.authorizeAgreement(agreementId, body);
    }

    // ── Quebranto ────────────────────────────────────────────────────────────

    @PostMapping("/collections/cases/{caseId}/request-write-off")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Solicitar un quebranto — sólo publica la intención, no persiste")
    void requestWriteOff(@PathVariable UUID caseId, @RequestBody Map<String, Object> body) {
        collectionsClient.requestWriteOff(caseId, body);
    }

    @PostMapping("/collections/cases/{caseId}/write-offs")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Aprobar y ejecutar un quebranto — crea el acta inmutable")
    Object approveWriteOff(@PathVariable UUID caseId, @RequestBody Map<String, Object> body) {
        return collectionsClient.approveWriteOff(caseId, body);
    }

    // ── Buró y configuración ─────────────────────────────────────────────────

    @GetMapping("/collections/bureau-reports")
    @Operation(summary = "Reportes a buró pendientes o fallidos")
    Object bureauReports() {
        return collectionsClient.bureauReports();
    }

    @GetMapping("/collections/config")
    @Operation(summary = "Los límites configurados que la pantalla valida antes de enviar")
    Object config() {
        return collectionsClient.config();
    }

    // ── Vistas y hidratación ─────────────────────────────────────────────────

    private Map<String, Object> caseView(CollectionCaseResponse c, String obligorName) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("caseId", c.caseId() == null ? null : c.caseId().toString());
        m.put("creditAccountId", c.creditAccountId() == null ? null : c.creditAccountId().toString());
        m.put("obligorPartyId", c.obligorPartyId() == null ? null : c.obligorPartyId().toString());
        m.put("obligorName", obligorName);
        m.put("productType", c.productType());
        m.put("status", c.status());
        m.put("currentBucket", c.currentBucket());
        m.put("daysDelinquent", c.daysDelinquent());
        m.put("totalDebt", c.totalDebt());
        m.put("assignedAgentId", c.assignedAgentId());
        m.put("externalAgencyId", c.externalAgencyId());
        m.put("strategy", c.strategy());
        m.put("openedAt", c.openedAt() == null ? null : c.openedAt().toString());
        m.put("closedAt", c.closedAt() == null ? null : c.closedAt().toString());
        return m;
    }

    // ── Bandejas transversales ───────────────────────────────────────────────────────────────

    @Operation(summary = "Promesas de pago cruzando casos",
               description = "Bandeja de «promesas vigentes». Filtros: estado, tramo, gestor y "
                           + "rango de fecha prometida; `dueToday=true` acota a las de hoy. "
                           + "Cada fila trae si el caso es contactable ahora — ventana horaria y "
                           + "tope de intentos los decide cobranza, no esta consola.")
    @GetMapping("/collections/payment-promises")
    ResponseEntity<Map<String, Object>> promises(
            @RequestParam(required = false) List<String> status,
            @RequestParam(required = false) List<String> bucket,
            @RequestParam(required = false) List<String> agentId,
            @RequestParam(required = false) String dueFrom,
            @RequestParam(required = false) String dueTo,
            @RequestParam(required = false, defaultValue = "false") boolean dueToday,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        Map<String, Object> result = collectionsClient.searchPromises(
                status, bucket, agentId, dueFrom, dueTo, dueToday, page, size);
        return ResponseEntity.ok(withObligorNames(result));
    }

    @Operation(summary = "Intentos de contacto cruzando casos",
               description = "Bandeja de gestión: quién marcó, por qué canal y cómo terminó. "
                           + "Filtros: resultado, canal, tramo, gestor y rango de fecha.")
    @GetMapping("/collections/contact-attempts")
    ResponseEntity<Map<String, Object>> contactAttempts(
            @RequestParam(required = false) List<String> result,
            @RequestParam(required = false) List<String> channel,
            @RequestParam(required = false) List<String> bucket,
            @RequestParam(required = false) List<String> agentId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        Map<String, Object> body = collectionsClient.searchContactAttempts(
                result, channel, bucket, agentId, from, to, page, size);
        return ResponseEntity.ok(withObligorNames(body));
    }

    /**
     * Pone el nombre del obligado en cada fila de una bandeja, en dos consultas como mucho.
     *
     * <p>Deduplica antes de preguntar: una página de 50 promesas puede tener 50 casos pero muchos
     * menos obligados distintos, y en cualquier caso son dos llamadas por página — nunca una por
     * fila, que es lo que estas bandejas vienen a eliminar.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> withObligorNames(Map<String, Object> pageBody) {
        if (pageBody == null) return Map.of();
        Object raw = pageBody.get("content");
        if (!(raw instanceof List<?> list) || list.isEmpty()) return pageBody;

        List<Map<String, Object>> rows = (List<Map<String, Object>>) list;
        Set<UUID> ids = rows.stream()
                .map(r -> asUuid(r.get("obligorPartyId")))
                .filter(Objects::nonNull).collect(Collectors.toCollection(LinkedHashSet::new));

        Map<UUID, String> names = new HashMap<>();
        if (!ids.isEmpty()) {
            try {
                for (var p : safe(partyClient.batch(ids, "prospectId"))) {
                    if (p.prospectId() != null) names.put(p.prospectId(), BackofficeViews.fullName(p));
                }
                Set<UUID> unresolved = ids.stream()
                        .filter(id -> !names.containsKey(id)).collect(Collectors.toSet());
                if (!unresolved.isEmpty()) {
                    for (var p : safe(partyClient.batch(unresolved, "partyId"))) {
                        if (p.partyId() != null) names.put(p.partyId(), BackofficeViews.fullName(p));
                    }
                }
            } catch (Exception ex) {
                // Un nombre que no resuelve no puede tumbar la bandeja: lo que el gestor necesita
                // para trabajar —monto, fecha, tramo, si puede marcar— ya vino del dueño.
                log.warn("No se pudieron resolver nombres de obligados en la bandeja: {}", ex.getMessage());
            }
        }

        List<Map<String, Object>> enriched = rows.stream().map(r -> {
            Map<String, Object> out = new LinkedHashMap<>(r);
            out.put("obligorName", names.get(asUuid(r.get("obligorPartyId"))));
            return out;
        }).toList();

        Map<String, Object> body = new LinkedHashMap<>(pageBody);
        body.put("content", enriched);
        return body;
    }

    private static UUID asUuid(Object value) {
        if (value == null) return null;
        try {
            return value instanceof UUID u ? u : UUID.fromString(value.toString());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /**
     * Los obligados de la página en dos consultas como mucho.
     *
     * <p>Se pregunta primero por {@code prospectId} y luego por {@code partyId} con los que
     * quedaron sin resolver, igual que hace cartera: el identificador que arrastra un caso puede ser
     * cualquiera de los dos según por dónde entró la cuenta.
     */
    private Map<UUID, String> resolveNames(List<CollectionCaseResponse> rows) {
        Set<UUID> ids = rows.stream().map(CollectionCaseResponse::obligorPartyId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) return Map.of();

        Map<UUID, String> names = new HashMap<>();
        try {
            for (var p : safe(partyClient.batch(ids, "prospectId"))) {
                if (p.prospectId() != null) names.put(p.prospectId(), BackofficeViews.fullName(p));
            }
            Set<UUID> unresolved = ids.stream()
                    .filter(id -> !names.containsKey(id)).collect(Collectors.toSet());
            if (!unresolved.isEmpty()) {
                for (var p : safe(partyClient.batch(unresolved, "partyId"))) {
                    if (p.partyId() != null) names.put(p.partyId(), BackofficeViews.fullName(p));
                }
            }
        } catch (Exception ex) {
            log.warn("No se pudieron resolver nombres de obligados en lote: {}", ex.getMessage());
        }
        return names;
    }

    private String nameOf(UUID obligorPartyId) {
        if (obligorPartyId == null) return null;
        return resolveNames(List.of(new CollectionCaseResponse(
                null, null, obligorPartyId, null, null, null, 0, null, null, null, null, null, null)))
                .get(obligorPartyId);
    }

    private static <T> List<T> safe(List<T> xs) { return xs == null ? List.of() : xs; }
}

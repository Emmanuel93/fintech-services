package com.fintech.channelbackoffice.infrastructure.adapter.in.api;

import com.fintech.channelbackoffice.infrastructure.adapter.out.client.BankingClient;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.DisbursementClient;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.StpClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Bancos / Tesorería: por qué cuenta sale el dinero y en qué eslabón va cada pago.
 *
 * <h2>Por qué existe</h2>
 *
 * La pregunta «¿por qué cuenta salió este pago, llegó, y a nombre de quién?» exigía abrir cuatro
 * servicios por la red interna: banking para la cuenta y la ruta, disbursement para la orden,
 * stp para el SPEI y su CEP, y banking otra vez para la traza. Esta pantalla los junta, sólo para
 * leer — dar de alta una CLABE ordenante o apuntarle una ruta sigue siendo ADMIN en banking.
 *
 * <h2>Composición (patrón A)</h2>
 *
 * Los listados se resuelven en su dueño. La ficha de un pago suma <b>una llamada por fuente</b> y
 * cada bloque se degrada solo: si stp no contesta, la ficha sale sin orden SPEI y con el pago y la
 * traza; si banking no contesta, sin traza. Sólo un 404 del pago tumba la ficha, porque entonces no
 * hay de qué hablar.
 *
 * <p>Ninguna CLABE completa pasa por aquí: banking, disbursement y stp ya las mandan enmascaradas.
 */
@RestController
@Tag(name = "Tesorería", description = "Bancos / Tesorería — cuentas propias, rutas, pagos y su traza (sólo lectura)")
class TreasuryController {

    private static final Logger log = LoggerFactory.getLogger(TreasuryController.class);

    private static final int MAX_PAGE_SIZE = 200;

    private final BankingClient banking;
    private final DisbursementClient disbursements;
    private final StpClient stp;

    TreasuryController(BankingClient banking, DisbursementClient disbursements, StpClient stp) {
        this.banking       = banking;
        this.disbursements = disbursements;
        this.stp           = stp;
    }

    // ── Cuentas y rutas ──────────────────────────────────────────────────────────────────────

    @Operation(summary = "Cuentas bancarias propias",
               description = "CLABE enmascarada. `provider` sale de la ruta activa de mayor prioridad que "
                           + "apunta a la cuenta; nulo si ninguna la usa o si las rutas no contestan. "
                           + "`ledgerAccountName` es nulo: banking guarda la cuenta contable por código "
                           + "y contabilidad no expone su catálogo.")
    @GetMapping("/treasury/bank-accounts")
    List<BankAccountView> bankAccounts() {
        List<BankingClient.BankAccount> accounts = banking.bankAccounts();
        Map<UUID, String> providerByAccount = safely("las rutas de pago", banking::payoutRoutes,
                List.<BankingClient.PayoutRoute>of())
                .stream()
                .filter(BankingClient.PayoutRoute::enabled)
                .filter(r -> r.bankAccountId() != null)
                .sorted(Comparator.comparingInt(BankingClient.PayoutRoute::priority))
                .collect(Collectors.toMap(BankingClient.PayoutRoute::bankAccountId,
                        r -> r.provider() == null ? "" : r.provider(), (primera, otra) -> primera));
        return orEmpty(accounts).stream()
                .map(a -> new BankAccountView(a.bankAccountId(), a.institutionName(), a.institutionCode(),
                        a.clabe(), a.holderName(), a.ledgerAccount(), null, a.status(),
                        blankToNull(providerByAccount.get(a.bankAccountId()))))
                .toList();
    }

    @Operation(summary = "Rutas de pago", description = "Por qué cuenta y proveedor sale cada pago según rail y monto.")
    @GetMapping("/treasury/payout-routes")
    List<PayoutRouteView> payoutRoutes() {
        return orEmpty(banking.payoutRoutes()).stream()
                .map(r -> new PayoutRouteView(r.payoutRouteId(), r.rail(), r.provider(),
                        r.minAmount(), r.maxAmount(), r.priority(), r.enabled()))
                .toList();
    }

    // ── Pagos ────────────────────────────────────────────────────────────────────────────────

    @Operation(summary = "Pagos salientes",
               description = "Del más nuevo al más viejo. `creditAccountId` filtra por crédito; `q` busca en "
                           + "el nombre del beneficiario y en la clave de rastreo.")
    @GetMapping("/treasury/disbursements")
    DisbursementPageView disbursements(@RequestParam(required = false) UUID creditAccountId,
                                       @RequestParam(required = false) String q,
                                       @RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "20") int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        DisbursementClient.DisbursementPage result =
                disbursements.search(creditAccountId, q, safePage, safeSize);
        if (result == null) {
            return new DisbursementPageView(List.of(), safePage, safeSize, 0, 0);
        }
        return new DisbursementPageView(
                orEmpty(result.content()).stream().map(TreasuryController::row).toList(),
                result.page(), result.size(), result.totalElements(), result.totalPages());
    }

    @Operation(summary = "Ficha de un pago",
               description = "El pago, su bitácora, la orden SPEI y la traza del dinero. Cada bloque es "
                           + "independiente: si una fuente no responde, ese bloque viene nulo y el resto sigue.")
    @GetMapping("/treasury/disbursements/{disbursementId}")
    DisbursementDetailView disbursement(@PathVariable UUID disbursementId) {
        DisbursementRow row = disbursementBlock(disbursementId);
        List<TimelineEntry> timeline = safely("la bitácora del pago " + disbursementId,
                () -> orEmpty(disbursements.timeline(disbursementId)).stream()
                        .sorted(Comparator.comparing(DisbursementClient.DisbursementEvent::occurredAt,
                                Comparator.nullsLast(Comparator.naturalOrder())))
                        .map(e -> new TimelineEntry(e.toStatus(), e.occurredAt(), e.detail()))
                        .toList(),
                null);
        StpOrderView stpOrder = safely("la orden STP del pago " + disbursementId,
                () -> stp.paymentOrder(disbursementId).map(TreasuryController::stpOrder).orElse(null),
                null);
        MovementTraceView trace = safely("la traza del pago " + disbursementId,
                () -> banking.traceByPayout(disbursementId).map(TreasuryController::trace).orElse(null),
                null);
        return new DisbursementDetailView(row, timeline, stpOrder, trace);
    }

    /**
     * El pago mismo. Un 404 se propaga —no existe el pago, no hay ficha—; cualquier otra falla deja
     * el bloque nulo, porque la orden STP y la traza se piden por el mismo id y siguen sirviendo.
     */
    private DisbursementRow disbursementBlock(UUID disbursementId) {
        try {
            DisbursementClient.Disbursement d = disbursements.findById(disbursementId);
            return d == null ? null : row(d);
        } catch (ResponseStatusException e) {
            if (e.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) throw e;
            log.warn("No se pudo leer el pago {} para tesorería: {}", disbursementId, e.toString());
            return null;
        } catch (RuntimeException e) {
            log.warn("No se pudo leer el pago {} para tesorería: {}", disbursementId, e.toString());
            return null;
        }
    }

    // ── Traducción ───────────────────────────────────────────────────────────────────────────

    private static DisbursementRow row(DisbursementClient.Disbursement d) {
        Map<String, String> meta = d.sourceMetadata() == null ? Map.of() : d.sourceMetadata();
        return new DisbursementRow(d.disbursementId(), d.createdAt(), d.amount(), d.currency(),
                d.rail(), d.provider(), d.status(), d.externalRef(), d.beneficiaryName(),
                d.beneficiaryAccountMasked(), uuid(meta.get("creditAccountId")),
                uuid(meta.get("dispositionId")), d.settledAt(), d.receiptUrl());
    }

    private static StpOrderView stpOrder(StpClient.PaymentOrder o) {
        return new StpOrderView(o.stpOrderId(), o.trackingKey(), o.orderingAccountMasked(),
                o.orderingHolderName(), o.beneficiaryName(), o.beneficiaryAccountMasked(), o.amount(),
                o.status(), o.cepUrl(), o.settledAt());
    }

    /**
     * Los tramos de la cadena, en orden. {@code missingLink} es el de banking tal cual: dice en qué
     * eslabón se detuvo — y hoy conciliar y asentar no tienen productor, así que una traza sana se
     * queda en {@code SIN_CONCILIAR} y lo dice, en vez de fingirse completa.
     */
    private static MovementTraceView trace(BankingClient.Trace t) {
        List<TraceStep> steps = List.of(
                step("REQUESTED",  t.createdAt(),    t.sourceReference() != null ? t.sourceReference() : str(t.payoutId()),
                        t.payoutId() != null),
                step("ROUTED",     t.routedAt(),     str(t.bankAccountId()), t.bankAccountId() != null),
                step("DISPATCHED", t.dispatchedAt(), t.trackingKey(),        t.trackingKey() != null),
                step("SETTLED",    t.settledAt(),    null,                   t.settledAt() != null),
                step("RECONCILED", t.reconciledAt(), str(t.lineId()),        t.lineId() != null),
                step("POSTED",     t.postedAt(),     t.voucherRef(),         t.voucherRef() != null));
        return new MovementTraceView(t.complete(), t.missingLink(), steps);
    }

    private static TraceStep step(String name, OffsetDateTime at, String reference, boolean done) {
        return new TraceStep(name, done ? "DONE" : "PENDING", at, done ? reference : null);
    }

    /** Una fuente auxiliar que falla deja su bloque en {@code fallback} y lo registra. */
    private static <T> T safely(String what, Supplier<T> call, T fallback) {
        try {
            return call.get();
        } catch (RuntimeException e) {
            log.warn("No se pudo leer {} para tesorería: {}", what, e.toString());
            return fallback;
        }
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : list.stream().filter(Objects::nonNull).toList();
    }

    private static String str(UUID id) {
        return id == null ? null : id.toString();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    /** El eco de metadatos llega como texto, y a veces como {@code "null"} literal. */
    private static UUID uuid(String raw) {
        if (raw == null || raw.isBlank() || "null".equals(raw)) return null;
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ── Contrato con la consola ──────────────────────────────────────────────────────────────

    record BankAccountView(UUID bankAccountId, String bankName, String bankCode, String clabeMasked,
                           String holderName, String ledgerAccountCode, String ledgerAccountName,
                           String status, String provider) {}

    record PayoutRouteView(UUID routeId, String rail, String provider, BigDecimal minAmount,
                           BigDecimal maxAmount, int priority, boolean active) {}

    record DisbursementRow(UUID disbursementId, Instant createdAt, BigDecimal amount, String currency,
                           String rail, String provider, String status, String trackingKey,
                           String beneficiaryName, String beneficiaryClabeMasked, UUID creditAccountId,
                           UUID dispositionId, Instant settledAt, String cepUrl) {}

    record DisbursementPageView(List<DisbursementRow> content, int page, int size,
                                long totalElements, int totalPages) {}

    record TimelineEntry(String status, Instant occurredAt, String detail) {}

    record StpOrderView(String stpOrderId, String trackingKey, String orderingClabeMasked,
                        String orderingName, String beneficiaryName, String beneficiaryClabeMasked,
                        BigDecimal amount, String status, String cepUrl, Instant settledAt) {}

    record TraceStep(String step, String status, OffsetDateTime occurredAt, String reference) {}

    record MovementTraceView(boolean complete, String missingLink, List<TraceStep> steps) {}

    record DisbursementDetailView(DisbursementRow disbursement, List<TimelineEntry> timeline,
                                  StpOrderView stpOrder, MovementTraceView movementTrace) {}
}

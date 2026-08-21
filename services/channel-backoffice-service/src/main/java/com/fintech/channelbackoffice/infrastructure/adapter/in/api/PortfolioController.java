package com.fintech.channelbackoffice.infrastructure.adapter.in.api;

import com.fintech.channelbackoffice.infrastructure.adapter.out.client.CreditPortfolioClient;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.CreditPortfolioClient.CreditAccountResponse;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.PartyClient;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.PaymentsClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Cartera para el backoffice: el listado y el seguimiento de una cuenta
 * periodo a periodo.
 *
 * <p>Dos decisiones que definen esta clase:
 *
 * <ul>
 *   <li><b>La página la arma credit-portfolio.</b> Aquí no se recorta nada:
 *       traer la cartera completa para quedarse con 25 filas mueve la tabla
 *       entera por la red en cada carga.</li>
 *   <li><b>Los nombres de los obligados se resuelven en lote.</b> Una consulta
 *       a party por fila convierte un listado de 25 en 26 llamadas; se piden
 *       los ids distintos de la página y se arma un diccionario.</li>
 * </ul>
 */
@RestController
@Tag(name = "Cartera", description = "Listado y seguimiento de créditos vigentes")
class PortfolioController {

    private static final Logger log = LoggerFactory.getLogger(PortfolioController.class);

    private final CreditPortfolioClient creditPortfolioClient;
    private final PartyClient partyClient;
    private final PaymentsClient paymentsClient;

    PortfolioController(CreditPortfolioClient creditPortfolioClient, PartyClient partyClient,
                        PaymentsClient paymentsClient) {
        this.creditPortfolioClient = creditPortfolioClient;
        this.partyClient = partyClient;
        this.paymentsClient = paymentsClient;
    }

    @GetMapping("/portfolio")
    @Operation(summary = "Listado paginado de cartera",
            description = "Filtros opcionales por estado, producto, texto (número de contrato) y "
                        + "días de atraso. El tamaño de página lo acota el servicio de cartera a 100.")
    ResponseEntity<Map<String, Object>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String productType,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer minDaysDelinquent,
            @RequestParam(required = false) Integer maxDaysDelinquent,
            @RequestParam(required = false) String executiveId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "createdAt,desc") String sort) {

        // Filtro por ejecutivo: la asignación vive en party, no en cartera. Se
        // resuelven los obligados del ejecutivo (2 llamadas acotadas, no una por
        // fila) y se pasan como partyIds. Sin clientes → cartera vacía, no toda.
        List<UUID> partyIds = null;
        if (executiveId != null && !executiveId.isBlank()) {
            partyIds = obligorIdsOfExecutive(executiveId);
            if (partyIds.isEmpty()) {
                return ResponseEntity.ok(emptyPage(page, size));
            }
        }

        var result = creditPortfolioClient.search(
                status, productType, q, minDaysDelinquent, maxDaysDelinquent, partyIds, page, size, sort);
        List<CreditAccountResponse> rows = result == null || result.content() == null
                ? List.of() : result.content();

        Map<UUID, String> names = resolveNames(rows);

        List<Map<String, Object>> content = rows.stream()
                .map(a -> BackofficeViews.account(a, names.get(a.obligorPartyId())))
                .toList();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("content", content);
        body.put("page", result == null ? page : result.number());
        body.put("size", result == null ? size : result.size());
        body.put("totalElements", result == null ? 0 : result.totalElements());
        body.put("totalPages", result == null ? 0 : result.totalPages());
        return ResponseEntity.ok(body);
    }

    @GetMapping("/portfolio/{creditAccountId}")
    @Operation(summary = "Detalle de una cuenta con su calendario",
            description = "Incluye el plan de pagos completo — es el seguimiento periodo a "
                        + "periodo— y las disposiciones. El calendario se pide sólo aquí: en el "
                        + "listado sería una consulta por fila.")
    ResponseEntity<Map<String, Object>> detail(@PathVariable UUID creditAccountId) {
        log.info("GET /portfolio/{}", creditAccountId);
        CreditAccountResponse account = creditPortfolioClient.getById(creditAccountId);
        String name = account == null ? null : nameOf(account.obligorPartyId());

        Map<String, Object> body = new LinkedHashMap<>(BackofficeViews.account(account, name));

        List<Map<String, Object>> schedule = new ArrayList<>();
        for (var i : safe(creditPortfolioClient.schedule(creditAccountId))) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("installmentNumber", i.installmentNumber());
            row.put("dueDate", i.dueDate() == null ? null : i.dueDate().toString());
            row.put("principalAmount", i.principalAmount());
            row.put("interestAmount", i.interestAmount());
            row.put("totalAmount", i.totalAmount());
            row.put("status", i.status());
            schedule.add(row);
        }
        body.put("schedule", schedule);

        List<Map<String, Object>> dispositions = new ArrayList<>();
        for (var d : safe(creditPortfolioClient.dispositions(creditAccountId))) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("dispositionId", d.dispositionId() == null ? null : d.dispositionId().toString());
            row.put("amount", d.amount());
            row.put("type", d.dispositionType());
            row.put("status", d.status());
            row.put("createdAt", d.createdAt() == null ? null : d.createdAt().toString());
            dispositions.add(row);
        }
        body.put("dispositions", dispositions);

        // Pagos aplicados. Es una llamada por cuenta —no por fila— y se degrada sola: si payments
        // no responde, la ficha pierde la lista de pagos pero conserva saldos y calendario. Una
        // pantalla incompleta sirve; una pantalla en blanco por un servicio secundario, no.
        List<Map<String, Object>> payments = new ArrayList<>();
        try {
            for (var p : safe(paymentsClient.listByAccount(creditAccountId))) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("paymentOrderId", p.paymentOrderId() == null ? null : p.paymentOrderId().toString());
                row.put("amount", p.amount());
                row.put("paymentMethod", p.paymentMethod());
                row.put("status", p.status());
                row.put("externalRef", p.externalRef());
                row.put("rejectionReason", p.rejectionReason() != null ? p.rejectionReason() : p.reversalReason());
                row.put("createdAt", p.createdAt() == null ? null : p.createdAt().toString());
                row.put("appliedAt", p.confirmedAt() == null ? null : p.confirmedAt().toString());
                payments.add(row);
            }
        } catch (Exception ex) {
            log.warn("Sin historial de pagos para la cuenta {}: {}", creditAccountId, ex.getMessage());
        }
        body.put("payments", payments);

        // Provisión esperada del tramo. Misma escala que el tablero, para que la
        // ficha y el resumen no puedan discrepar.
        String stage = BackofficeViews.ifrs9Stage(account == null ? 0 : account.daysDelinquent());
        BigDecimal rate = BackofficeViews.expectedLossRate(stage);
        BigDecimal principal = account == null || account.principalBalance() == null
                ? BigDecimal.ZERO : account.principalBalance();
        body.put("expectedLossRate", rate);
        body.put("provisionAmount", BackofficeViews.round2(principal.multiply(rate)));

        return ResponseEntity.ok(body);
    }

    /**
     * Nombres de los obligados de la página, en un diccionario, <b>en lote</b>.
     *
     * <p>El invariante: el nº de llamadas no depende del nº de filas. Se piden los
     * ids <b>distintos</b> de la página en <b>una</b> llamada a party (cartera
     * guarda el prospectId como obligado, así que se pregunta por prospecto); los
     * pocos que no resuelvan —parties creadas por otro camino— se piden en una
     * segunda llamada por partyId. Dos llamadas como mucho, no una por fila. Un
     * fallo del lote deja las filas sin nombre, mejor que una pantalla en blanco.
     */
    private Map<UUID, String> resolveNames(List<CreditAccountResponse> rows) {
        Set<UUID> ids = rows.stream().map(CreditAccountResponse::obligorPartyId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
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

    /**
     * Nombre del obligado de UNA cuenta (para la ficha). Cartera guarda el id del
     * prospecto, así que se busca por ahí primero y se cae a partyId si no existe.
     */
    private String nameOf(UUID obligorId) {
        if (obligorId == null) return null;
        try {
            var p = partyClient.getByProspectId(obligorId);
            if (p == null) p = partyClient.getByPartyId(obligorId);
            return p == null ? null : BackofficeViews.fullName(p);
        } catch (Exception ex) {
            log.warn("Sin nombre para el obligado {}: {}", obligorId, ex.getMessage());
            return null;
        }
    }

    /**
     * Los ids de obligado (prospectId, como los guarda cartera) de los clientes
     * de un ejecutivo. Página amplia acotada: un ejecutivo no lleva miles.
     */
    private List<UUID> obligorIdsOfExecutive(String executiveId) {
        var clients = partyClient.search(null, null, null, executiveId, 0, 500, "createdAt,desc");
        if (clients == null || clients.content() == null) {
            return List.of();
        }
        return clients.content().stream()
                .map(p -> p.prospectId() != null ? p.prospectId() : p.partyId())
                .filter(Objects::nonNull)
                .toList();
    }

    private static Map<String, Object> emptyPage(int page, int size) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("content", List.of());
        body.put("page", page);
        body.put("size", size);
        body.put("totalElements", 0L);
        body.put("totalPages", 0);
        return body;
    }

    private static <T> List<T> safe(List<T> xs) { return xs == null ? List.of() : xs; }
}

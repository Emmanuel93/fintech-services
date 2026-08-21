package com.fintech.channelbackoffice.infrastructure.adapter.in.api;

import com.fintech.channelbackoffice.infrastructure.adapter.out.client.CommissionClient;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.CreditPortfolioClient;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.PartyClient;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.SalesOrgClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Los distribuidores, vistos desde el distribuidor hacia sus créditos.
 *
 * <p>La consola ya llamaba a {@code /distributors} y {@code /distributors/{partyId}} y el BFF no
 * los exponía: la pestaña «Distribuidores» funcionaba contra datos simulados y devolvía 404 contra
 * el gateway real. Esto los pone.
 *
 * <p><b>De dónde sale cada dato</b>, porque no hay un servicio de distribuidores y no debería
 * haberlo — un distribuidor no es una entidad nueva, es la intersección de cuatro que ya existen:
 *
 * <ul>
 *   <li><b>sales-org</b> — quién es distribuidor y con qué código: los nodos de nivel
 *       {@code DISTRIBUTOR} del árbol, cuyo {@code partyRef} apunta a su party.</li>
 *   <li><b>party</b> — el nombre y el RFC.</li>
 *   <li><b>commission</b> — qué créditos colocó (la atribución por {@code promoterCode}) y cuánta
 *       comisión lleva devengada.</li>
 *   <li><b>credit-portfolio</b> — su propia línea revolvente y los saldos de lo que colocó.</li>
 * </ul>
 *
 * <p>La dirección de la consulta no es un detalle: se pregunta por el distribuidor y se llega a sus
 * créditos, nunca al revés. Quien usa la consola recuerda el nombre o el código del distribuidor;
 * el número de contrato no lo recuerda nadie.
 */
@RestController
@RequestMapping("/distributors")
@Tag(name = "Distribuidores", description = "Distribuidores B2B2C, su línea y lo que colocaron")
class DistributorsController {

    private static final Logger log = LoggerFactory.getLogger(DistributorsController.class);

    /** El nivel del árbol en el que vive un distribuidor (profundidad 5, bajo su ejecutivo). */
    private static final String NIVEL_DISTRIBUIDOR = "DISTRIBUTOR";
    private static final String LINEA_DISTRIBUIDORA = "DISTRIBUTOR_LINE";

    private final SalesOrgClient salesOrgClient;
    private final PartyClient partyClient;
    private final CommissionClient commissionClient;
    private final CreditPortfolioClient creditPortfolioClient;

    DistributorsController(SalesOrgClient salesOrgClient,
                           PartyClient partyClient,
                           CommissionClient commissionClient,
                           CreditPortfolioClient creditPortfolioClient) {
        this.salesOrgClient        = salesOrgClient;
        this.partyClient           = partyClient;
        this.commissionClient      = commissionClient;
        this.creditPortfolioClient = creditPortfolioClient;
    }

    @GetMapping
    @Operation(summary = "Distribuidores, paginados",
            description = "Cada fila trae su línea, lo colocado y la comisión devengada.")
    Map<String, Object> list(@RequestParam(required = false) String q,
                             @RequestParam(required = false) Boolean active,
                             @RequestParam(defaultValue = "0") int page,
                             @RequestParam(defaultValue = "25") int size) {

        List<Nodo> nodos = nodosDistribuidores();
        if (active != null) {
            nodos = nodos.stream().filter(n -> n.activo() == active).toList();
        }

        // Los datos de persona se piden en lote, no uno por uno: son decenas de distribuidores y
        // una llamada por cada uno convierte una pantalla en una tormenta de peticiones.
        Map<UUID, PartyClient.PartyResponse> personas = personasDe(
                nodos.stream().map(Nodo::partyRef).collect(Collectors.toSet()));

        // El filtro de texto se aplica DESPUÉS de hidratar: la gente busca por nombre o por RFC, y
        // eso sólo se conoce al juntar el nodo con su party.
        List<Map<String, Object>> filas = nodos.stream()
                .map(n -> fila(n, personas.get(n.partyRef())))
                .filter(f -> coincide(f, q))
                .sorted(Comparator.comparing(f -> String.valueOf(f.get("fullName"))))
                .toList();

        int desde = Math.min(page * size, filas.size());
        int hasta = Math.min(desde + size, filas.size());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("content", filas.subList(desde, hasta));
        body.put("number", page);
        body.put("size", size);
        body.put("totalElements", filas.size());
        body.put("totalPages", Math.max(1, (int) Math.ceil(filas.size() / (double) size)));
        return body;
    }

    @GetMapping("/{partyId}")
    @Operation(summary = "Ficha del distribuidor: su línea y los créditos que colocó")
    Map<String, Object> detail(@PathVariable UUID partyId) {
        Nodo nodo = nodosDistribuidores().stream()
                .filter(n -> partyId.equals(n.partyRef()))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "No hay ningún distribuidor con partyId=" + partyId));

        Map<String, Object> ficha = new LinkedHashMap<>(
                fila(nodo, personasDe(List.of(partyId)).get(partyId)));

        // Los créditos colocados: de commission salen los ids, de cartera los saldos. Se hidratan
        // en lote por la misma razón que arriba.
        List<CommissionClient.PromoterCredit> colocados =
                commissionClient.creditsByPromoters(List.of(partyId));
        List<UUID> ids = colocados.stream()
                .map(CommissionClient.PromoterCredit::creditAccountId).toList();

        List<Map<String, Object>> credits = ids.isEmpty() ? List.of()
                : creditPortfolioClient.batch(ids).stream()
                    .map(DistributorsController::credito)
                    .sorted(Comparator.comparing(c -> String.valueOf(c.get("contractNumber"))))
                    .toList();

        ficha.put("credits", credits);
        return ficha;
    }

    // ── Composición ────────────────────────────────────────────────────────────

    /** Un nodo del árbol que representa a un distribuidor. */
    private record Nodo(UUID unitId, UUID partyRef, String code, String name, boolean activo) {}

    /**
     * Los nodos de nivel DISTRIBUTOR del árbol.
     *
     * <p>El nivel se resuelve por su código y no por la profundidad escrita a mano: la escalera es
     * configurable y un 5 incrustado aquí dejaría de significar «distribuidor» en cuanto alguien
     * insertara un nivel intermedio, sin que nada avisara.
     */
    private List<Nodo> nodosDistribuidores() {
        UUID nivelId = salesOrgClient.listLevels().stream()
                .filter(l -> NIVEL_DISTRIBUIDOR.equals(String.valueOf(l.get("code"))))
                .map(l -> UUID.fromString(String.valueOf(l.get("levelId"))))
                .findFirst()
                .orElse(null);

        if (nivelId == null) {
            log.warn("El árbol comercial no tiene nivel {} — no hay distribuidores que listar",
                    NIVEL_DISTRIBUIDOR);
            return List.of();
        }

        return salesOrgClient.listUnits().stream()
                .filter(u -> nivelId.toString().equals(String.valueOf(u.get("levelId"))))
                // Un nodo de distribuidor sin partyRef no es un distribuidor: es una unidad a medio
                // dar de alta. Se omite en vez de producir una fila sin persona detrás.
                .filter(u -> u.get("partyRef") != null)
                .map(u -> new Nodo(
                        UUID.fromString(String.valueOf(u.get("unitId"))),
                        UUID.fromString(String.valueOf(u.get("partyRef"))),
                        String.valueOf(u.get("code")),
                        String.valueOf(u.get("name")),
                        !Boolean.FALSE.equals(u.get("active"))))
                .toList();
    }

    /**
     * Los expedientes, buscados por <b>prospectId</b>.
     *
     * <p>Una persona arrastra dos identificadores en este sistema: el {@code partyId} de su
     * expediente y el {@code prospectId} con el que nació, y **cartera, commission y el árbol
     * comercial hablan en el segundo** — {@code credit_accounts.obligor_party_id} guarda el
     * prospectId, no el partyId. Por eso {@code org_units.party_ref} lleva el prospectId: es lo
     * único que cruza con una cuenta de crédito.
     *
     * <p>Buscar por {@code partyId} devolvía cero personas y las filas salían con el nombre del
     * nodo en vez del de la persona — un fallo que no rompe la pantalla, sólo la vuelve mentira.
     */
    private Map<UUID, PartyClient.PartyResponse> personasDe(Collection<UUID> refs) {
        if (refs.isEmpty()) return Map.of();
        return partyClient.batch(refs, "prospectId").stream()
                .filter(p -> p.prospectId() != null)
                .collect(Collectors.toMap(PartyClient.PartyResponse::prospectId, p -> p, (a, b) -> a));
    }

    private Map<String, Object> fila(Nodo nodo, PartyClient.PartyResponse persona) {
        Map<String, Object> f = new LinkedHashMap<>();
        // `partyId` es lo que la consola usa como clave del distribuidor y lo que cruza con la
        // cartera: el prospectId. `expedientePartyId` es la clave del expediente en party-service,
        // que es otra y hace falta para los roles y las relaciones.
        f.put("partyId",      nodo.partyRef().toString());
        f.put("expedientePartyId", persona != null && persona.partyId() != null
                ? persona.partyId().toString() : null);
        f.put("unitId",       nodo.unitId().toString());
        f.put("promoterCode", nodo.code());
        f.put("active",       nodo.activo());

        f.put("fullName", persona != null ? nombre(persona) : nodo.name());
        f.put("partyType", persona != null ? persona.partyType() : null);
        f.put("rfc",       persona != null ? persona.rfc()   : null);
        f.put("curp",      persona != null ? persona.curp()  : null);
        f.put("status",    persona != null ? persona.status(): null);
        f.put("riskLevel", persona != null ? persona.riskLevel() : null);
        // `totalScore` lo declara el tipo `Client` de la consola, del que `Distributor` extiende.
        // Omitirlo no rompe la pantalla —llega undefined— pero deja una columna vacía que parece
        // un distribuidor sin calificar en vez de un campo que nadie mandó.
        f.put("totalScore", persona != null ? persona.totalScore() : null);
        f.put("assignedExecutiveId",
                persona != null && persona.assignedExecutiveId() != null
                        ? persona.assignedExecutiveId().toString() : null);
        f.put("assignedExecutiveName", persona != null ? persona.assignedExecutiveName() : null);
        f.put("createdAt", persona != null ? persona.createdAt() : null);

        // Su propia línea revolvente. Un distribuidor puede además tener crédito de uso propio, así
        // que no vale tomar «la primera cuenta»: hay que quedarse con la DISTRIBUTOR_LINE.
        List<CreditPortfolioClient.CreditAccountResponse> propias = seguro(
                () -> creditPortfolioClient.listByParty(nodo.partyRef()), List.of());
        CreditPortfolioClient.CreditAccountResponse linea = propias.stream()
                .filter(a -> LINEA_DISTRIBUIDORA.equals(a.productType()))
                .findFirst().orElse(null);

        f.put("creditLineId",        linea != null ? linea.creditAccountId().toString() : null);
        f.put("creditLineLimit",     linea != null ? linea.creditLimit()     : null);
        f.put("creditLineAvailable", linea != null ? linea.availableCredit() : null);
        f.put("creditLineStatus",    linea != null ? linea.status()          : null);
        f.put("daysDelinquent",      linea != null ? linea.daysDelinquent()  : 0);

        // Lo colocado a terceros.
        List<CommissionClient.PromoterCredit> colocados = seguro(
                () -> commissionClient.creditsByPromoters(List.of(nodo.partyRef())), List.of());
        f.put("originatedCredits", colocados.size());
        f.put("originatedAmount", montoColocado(colocados));

        // La comisión. Devengada = ACCRUED sin liquidar; pagada = el resto de lo que devolvió
        // commission. Se devenga contra el pago, no contra la colocación (CM-01).
        List<CommissionClient.CommissionRecord> comisiones = commissionClient.pendingOf(nodo.partyRef());
        f.put("commissionAccrued", suma(comisiones, "ACCRUED"));
        f.put("commissionPaid",    suma(comisiones, "LIQUIDATED"));

        return f;
    }

    private BigDecimal montoColocado(List<CommissionClient.PromoterCredit> colocados) {
        if (colocados.isEmpty()) return BigDecimal.ZERO;
        List<UUID> ids = colocados.stream()
                .map(CommissionClient.PromoterCredit::creditAccountId).toList();
        return seguro(() -> creditPortfolioClient.batch(ids), List.<CreditPortfolioClient.CreditAccountResponse>of())
                .stream()
                .map(a -> a.principalBalance() == null ? BigDecimal.ZERO : a.principalBalance())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static Map<String, Object> credito(CreditPortfolioClient.CreditAccountResponse a) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("creditAccountId", a.creditAccountId().toString());
        c.put("contractNumber",  a.contractNumber());
        c.put("obligorPartyId",  a.obligorPartyId() == null ? null : a.obligorPartyId().toString());
        c.put("productType",     a.productType());
        c.put("status",          a.status());
        c.put("principalBalance", a.principalBalance());
        c.put("daysDelinquent",  a.daysDelinquent());
        c.put("activatedAt",     a.activatedAt());
        return c;
    }

    private static BigDecimal suma(List<CommissionClient.CommissionRecord> rs, String estado) {
        return rs.stream()
                .filter(r -> estado.equals(r.status()))
                .map(r -> r.amount() == null ? BigDecimal.ZERO : r.amount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static boolean coincide(Map<String, Object> fila, String q) {
        if (q == null || q.isBlank()) return true;
        String needle = q.toLowerCase();
        return Stream.of("fullName", "promoterCode", "rfc", "curp")
                .map(fila::get)
                .filter(Objects::nonNull)
                .map(v -> String.valueOf(v).toLowerCase())
                .anyMatch(v -> v.contains(needle));
    }

    private static String nombre(PartyClient.PartyResponse p) {
        return Stream.of(p.firstName(), p.lastName1(), p.lastName2())
                .filter(s -> s != null && !s.isBlank())
                .collect(Collectors.joining(" "));
    }

    /**
     * Un servicio caído no debe vaciar la fila entera.
     *
     * <p>Esta vista junta cuatro dominios, así que la probabilidad de que uno falle es cuatro veces
     * la de una vista normal. Si commission no contesta, lo honesto es enseñar el distribuidor con
     * su línea y un cero en lo colocado, no un 502 que sugiere que no existe.
     */
    private static <T> T seguro(java.util.function.Supplier<T> llamada, T porDefecto) {
        try {
            T v = llamada.get();
            return v == null ? porDefecto : v;
        } catch (Exception e) {
            log.warn("Un servicio no respondió al armar la ficha de distribuidor: {}", e.getMessage());
            return porDefecto;
        }
    }
}

package com.fintech.channelbackoffice.infrastructure.adapter.in.api;

import com.fintech.channelbackoffice.application.CommercialPortfolioService;
import com.fintech.channelbackoffice.application.CommercialScopeService;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.CommissionClient;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.CreditPortfolioClient;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.CreditPortfolioClient.CreditAccountResponse;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.IdentityClient;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.SalesOrgClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.Set;
import java.util.UUID;

/**
 * Resumen de cartera para el tablero.
 *
 * <p>Todo sale de agregados que calcula la base en una sola pasada: es la
 * primera pantalla que abre cualquier usuario del backoffice, y sumar en
 * memoria obligaría a leer la cartera completa en cada carga de cada sesión.
 *
 * <p>El tablero global es agregado; el tablero <b>con alcance</b>
 * ({@code /dashboard/commercial}) lo acota a la unidad comercial del usuario: un
 * responsable de zona ve su zona y lo que cuelga de ella, no la red entera. El
 * alcance sale de sales-org (quién pertenece a qué unidad), y pedir una unidad
 * fuera del propio subárbol se rechaza con 403.
 */
@RestController
@Tag(name = "Tablero", description = "Indicadores de cartera")
class DashboardController {

    private static final Logger log = LoggerFactory.getLogger(DashboardController.class);

    private final CreditPortfolioClient creditPortfolioClient;
    private final SalesOrgClient salesOrgClient;
    private final CommissionClient commissionClient;
    private final IdentityClient identityClient;
    private final CommercialPortfolioService commercialPortfolioService;
    private final CommercialScopeService commercialScopeService;

    DashboardController(CreditPortfolioClient creditPortfolioClient, SalesOrgClient salesOrgClient,
                        CommissionClient commissionClient, IdentityClient identityClient,
                        CommercialPortfolioService commercialPortfolioService,
                        CommercialScopeService commercialScopeService) {
        this.creditPortfolioClient = creditPortfolioClient;
        this.salesOrgClient = salesOrgClient;
        this.commissionClient = commissionClient;
        this.identityClient = identityClient;
        this.commercialPortfolioService = commercialPortfolioService;
        this.commercialScopeService = commercialScopeService;
    }

    @GetMapping("/dashboard/summary")
    @Operation(summary = "Indicadores de cartera")
    ResponseEntity<Map<String, Object>> summary() {
        log.info("GET /dashboard/summary");
        var s = creditPortfolioClient.summary();

        BigDecimal principal = nz(s == null ? null : s.principalBalance());
        BigDecimal stage1 = nz(s == null ? null : s.stage1Principal());
        BigDecimal stage2 = nz(s == null ? null : s.stage2Principal());
        BigDecimal stage3 = nz(s == null ? null : s.stage3Principal());
        // Cartera vencida OFICIAL (CNBV/IFRS-9): 90+ días = stage3. El tramo 31–90 (stage2) es atraso
        // temprano (SICR), NO cartera vencida — mezclarlos era la causa de que la mora no cuadrara con
        // la vista de red. El IMOR (índice de morosidad) se calcula contra la vencida oficial.
        BigDecimal carteraVencida = stage3;
        BigDecimal atrasoTemprano = stage2;

        // Provisión por tramo, con la misma escala que la ficha de la cuenta.
        BigDecimal provision = stage1.multiply(BackofficeViews.expectedLossRate("STAGE_1"))
                .add(stage2.multiply(BackofficeViews.expectedLossRate("STAGE_2")))
                .add(stage3.multiply(BackofficeViews.expectedLossRate("STAGE_3")))
                .setScale(2, RoundingMode.HALF_UP);

        // IMOR en porcentaje (0-100): cartera vencida oficial / cartera total.
        BigDecimal delinquencyRate = principal.signum() == 0 ? BigDecimal.ZERO
                : carteraVencida.multiply(BigDecimal.valueOf(100))
                         .divide(principal, 2, RoundingMode.HALF_UP);

        // Cobranza del periodo (del calendario, agregada en cartera): a cobrar el próximo periodo y
        // qué % de lo esperado del periodo actual ya se cobró.
        BigDecimal esperado = nz(s == null ? null : s.esperadoPeriodoActual());
        BigDecimal cobrado = nz(s == null ? null : s.cobradoPeriodoActual());
        BigDecimal porcentajePagoPeriodo = esperado.signum() == 0 ? BigDecimal.ZERO
                : cobrado.multiply(BigDecimal.valueOf(100)).divide(esperado, 2, RoundingMode.HALF_UP);

        List<Map<String, Object>> byProduct = new ArrayList<>();
        for (var m : safe(creditPortfolioClient.productMix())) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("productType", m.productType());
            row.put("behavior", m.productBehavior());
            row.put("accounts", m.accounts());
            row.put("capital", m.capital());
            row.put("vencida", m.overdueCapital());
            byProduct.add(row);
        }

        Map<String, Object> health = new LinkedHashMap<>();
        health.put("stage1", stage1);
        health.put("stage2", stage2);
        health.put("stage3", stage3);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("capitalColocado", principal);
        body.put("totalDebt", nz(s == null ? null : s.totalDebt()));
        body.put("carteraSana", stage1);
        body.put("carteraVencida", carteraVencida);
        body.put("carteraAtrasoTemprano", atrasoTemprano);
        body.put("provisionTotal", provision);
        // EAD: exposición al incumplimiento. Sin factores de conversión por
        // producto, es el saldo total adeudado.
        body.put("ead", nz(s == null ? null : s.totalDebt()));
        body.put("activeAccounts", s == null ? 0 : s.activeAccounts());
        body.put("activeClients", s == null ? 0 : s.activeObligors());
        body.put("activeBusinesses", 0);
        body.put("delinquencyRate", delinquencyRate);
        body.put("aCobrarProximoPeriodo", nz(s == null ? null : s.aCobrarProximoPeriodo()));
        body.put("aCobrarPeriodoActual", esperado);
        body.put("cobradoPeriodoActual", cobrado);
        body.put("porcentajePagoPeriodo", porcentajePagoPeriodo);
        body.put("disbursedThisMonth", BigDecimal.ZERO);
        body.put("byProduct", byProduct);
        body.put("health", health);
        // byExecutive salía como [] (dato inexistente): se retira del resumen. El reparto por
        // ejecutivo/unidad es alcance, y vive en /dashboard/commercial (E5) apoyado en sales-org.
        body.put("pipeline", List.of());
        return ResponseEntity.ok(body);
    }

    @GetMapping("/dashboard/commercial/by-origin-unit")
    @Operation(summary = "Cartera por unidad de ORIGEN, acotada al subárbol",
            description = "Atribuye cada crédito a la unidad que lo **colocó**, sellada al activarlo "
                        + "(`origin_unit_code`) y que no cambia aunque la cartera se reasigne. "
                        + "Es una pregunta distinta a la de `/dashboard/commercial`, que atribuye "
                        + "por **ejecutivo actual**: mientras nada se reasigne las dos cifras "
                        + "coinciden, y en cuanto se reasigne una cartera dejarán de hacerlo. "
                        + "Por eso viajan en endpoints con nombres distintos y no en un mismo "
                        + "«capital por unidad» que nadie sabría cuál es.")
    ResponseEntity<Map<String, Object>> commercialByOriginUnit(
            @RequestParam(required = false) UUID unitId) {
        log.info("GET /dashboard/commercial/by-origin-unit unitId={}", unitId);

        UUID targetUnitId = commercialScopeService.resolveAndAuthorize(unitId);

        // 1 llamada a sales-org (el subárbol) + 1 a cartera (el agregado). No depende de cuántas
        // unidades cuelguen ni de cuántas cuentas tengan: O(1), no O(unidades).
        Map<String, String> codeNames = commercialScopeService.subtreeCodeNames(targetUnitId);
        List<CreditPortfolioClient.OriginUnitStat> stats =
                creditPortfolioClient.statsByOriginUnit(codeNames.keySet());

        List<Map<String, Object>> byUnit = new ArrayList<>();
        BigDecimal principal = BigDecimal.ZERO;
        BigDecimal stage1 = BigDecimal.ZERO, stage2 = BigDecimal.ZERO, stage3 = BigDecimal.ZERO;
        long accounts = 0, delinquent = 0;

        for (var st : stats) {
            byUnit.add(originUnitRow(st, codeNames.get(st.unitCode())));
            principal  = principal.add(nz(st.principal()));
            stage1     = stage1.add(nz(st.stage1Principal()));
            stage2     = stage2.add(nz(st.stage2Principal()));
            stage3     = stage3.add(nz(st.stage3Principal()));
            accounts  += st.accounts();
            delinquent += st.delinquentAccounts();
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("unitId", targetUnitId.toString());
        body.put("attribution", "ORIGIN_UNIT");
        body.put("attributionNote", "Unidad que colocó el crédito, sellada al activarlo. "
                + "No cambia si la cartera se reasigna.");
        // El total del subárbol es la suma de sus unidades: cada cuenta se atribuye a una sola
        // unidad de origen, así que el padre es exactamente la suma de sus descendientes. Es lo
        // que hace cotejable esta vista contra sí misma a cualquier altura del árbol.
        body.put("total", totals(accounts, delinquent, principal, stage1, stage2, stage3));
        body.put("byUnit", byUnit);
        return ResponseEntity.ok(body);
    }

    private static Map<String, Object> originUnitRow(CreditPortfolioClient.OriginUnitStat st, String name) {
        Map<String, Object> row = totals(st.accounts(), st.delinquentAccounts(), nz(st.principal()),
                nz(st.stage1Principal()), nz(st.stage2Principal()), nz(st.stage3Principal()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("unitCode", st.unitCode());
        out.put("unitName", name);
        out.putAll(row);
        return out;
    }

    /**
     * Los mismos nombres y la misma escala que {@code /dashboard/summary}.
     *
     * <p>«Vencida» es 90+ y nada más — el tramo 31–90 es atraso temprano y va con su propio
     * nombre. Mezclarlos fue lo que hizo que la misma cartera diera dos moras distintas según la
     * pantalla, y repetir aquí ese error sería repetirlo a propósito.
     */
    private static Map<String, Object> totals(long accounts, long delinquentAccounts,
                                              BigDecimal principal, BigDecimal stage1,
                                              BigDecimal stage2, BigDecimal stage3) {
        BigDecimal provision = stage1.multiply(BackofficeViews.expectedLossRate("STAGE_1"))
                .add(stage2.multiply(BackofficeViews.expectedLossRate("STAGE_2")))
                .add(stage3.multiply(BackofficeViews.expectedLossRate("STAGE_3")))
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal imor = principal.signum() == 0 ? BigDecimal.ZERO
                : stage3.multiply(BigDecimal.valueOf(100)).divide(principal, 2, RoundingMode.HALF_UP);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("accounts", accounts);
        m.put("delinquentAccounts", delinquentAccounts);
        m.put("principal", principal);
        m.put("carteraVencida", stage3);      // 90+, la oficial CNBV/IFRS-9
        m.put("atrasoTemprano", stage2);      // 31–90, SICR — no es vencida
        m.put("alCorriente", stage1);
        m.put("provision", provision);
        m.put("delinquencyRate", imor);
        return m;
    }

    @GetMapping("/dashboard/commercial")
    @Operation(summary = "Tablero con alcance por unidad comercial",
            description = "Acota a la unidad indicada (o a la del propio usuario). Un usuario "
                        + "comercial solo puede ver su unidad y su subárbol; pedir otra da 403. "
                        + "Los roles transversales (admin, riesgo, finanzas, auditoría) ven cualquiera.")
    ResponseEntity<Map<String, Object>> commercial(@RequestParam(required = false) UUID unitId,
                                                   HttpServletRequest http) {
        log.info("GET /dashboard/commercial unitId={}", unitId);

        UUID targetUnitId = commercialScopeService.resolveAndAuthorize(unitId);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("unitId", targetUnitId.toString());
        // El alcance: las asignaciones vigentes en todo el subárbol de la unidad (una consulta LTREE
        // en sales-org). Es "quién está en alcance"; los números de cartera los suma
        // `CommercialPortfolioService` **por ejecutivo actual**, con fan-out acotado y sin bucle por
        // fila. La atribución por unidad de ORIGEN —sellada al activar, y que puede dar otra cifra
        // de la misma cartera— vive aparte en `/dashboard/commercial/by-origin-unit`.
        // Copia profunda de una capa: más abajo se le añade a cada asignación su cartera y su
        // nombre. Copiar sólo la lista dejaba los mapas prestados de sales-org, y escribir en un
        // mapa que no es de uno funciona o revienta según cómo lo haya construido quien lo entregó
        // —con Jackson es mutable, con `Map.of` no—. Depender de eso es una avería esperando a que
        // cambie el decodificador.
        List<Map<String, Object>> scope = safe(salesOrgClient.scope(targetUnitId)).stream()
                .map(a -> (Map<String, Object>) new LinkedHashMap<String, Object>(a))
                .collect(Collectors.toCollection(ArrayList::new));
        long executives = scope.stream()
                .filter(a -> "STAFF".equals(a.get("assigneeType")))
                .map(a -> a.get("assigneeId")).filter(Objects::nonNull).distinct().count();
        fillAssigneeNames(scope, http);
        body.put("assignees", scope);
        body.put("executiveCount", executives);

        // Qué se colocó a través de un tercero. Se resuelve **antes** del rollup —y no después,
        // como estaba— porque el origen no es un bloque aparte al final de la pantalla: es un
        // atributo de cada rama. Una zona que coloca el 70% por distribuidores y otra que coloca
        // todo con su gente se dirigen distinto, y sumadas en un solo «capital colocado» esa
        // diferencia no se ve en ningún renglón.
        List<UUID> distributors = List.of();
        List<UUID> creditAccountIds = List.of();
        try {
            List<UUID> resolved = salesOrgClient.distributorsInSubtree(targetUnitId);
            if (resolved != null) distributors = resolved;
            creditAccountIds = commissionClient.creditsByPromoters(distributors).stream()
                    .map(CommissionClient.PromoterCredit::creditAccountId)
                    .filter(Objects::nonNull).distinct().toList();
        } catch (Exception ex) {
            log.warn("Sin distribuidores para la unidad {}: {}", targetUnitId, ex.getMessage());
        }

        // La cartera del subárbol, sumada por ejecutivo y subida por el árbol. Antes esto sólo
        // miraba distribuidores, y en una red donde los créditos cuelgan de ejecutivos el
        // resultado era un tablero de ceros junto a «57 ejecutivos»: contaba personas y no lo
        // que venden.
        var rollup = commercialPortfolioService.rollup(targetUnitId, scope, Set.copyOf(creditAccountIds));
        body.put("portfolio", rollup.total());
        body.put("byChildUnit", rollup.byChildUnit());
        body.put("byUnit", rollup.byUnit());
        body.put("unassigned", carteraSinTitular(targetUnitId, rollup.total()));

        // Cada asignado con lo suyo: es «cartera por ejecutivo», que hasta ahora era una tarjeta
        // vacía en el tablero remitiendo a esta pantalla.
        for (Map<String, Object> a : scope) {
            Object id = a.get("assigneeId");
            if (id == null) continue;
            Map<String, Object> suyo = rollup.byExecutive().get(id.toString());
            a.put("portfolio", suyo != null ? suyo : Map.of(
                    "accounts", 0, "delinquentAccounts", 0,
                    "principal", BigDecimal.ZERO, "overdue", BigDecimal.ZERO,
                    "delinquencyRate", BigDecimal.ZERO));
        }

        // El detalle cuenta por cuenta de lo colocado por terceros. El agregado ya viaja dentro de
        // cada rama del rollup; esto es la lista para abrirla.
        List<Map<String, Object>> distributorCartera = List.of();
        BigDecimal distributorPrincipal = BigDecimal.ZERO;
        try {
            List<CreditAccountResponse> accounts = safe(creditPortfolioClient.batch(creditAccountIds));
            distributorCartera = accounts.stream().map(a -> BackofficeViews.account(a, null)).toList();
            distributorPrincipal = accounts.stream()
                    .map(a -> a.principalBalance() == null ? BigDecimal.ZERO : a.principalBalance())
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        } catch (Exception ex) {
            log.warn("Sin cartera de distribuidores para la unidad {}: {}", targetUnitId, ex.getMessage());
        }
        body.put("distributorCount", distributors.size());
        body.put("distributorCartera", distributorCartera);
        body.put("distributorPrincipal", distributorPrincipal);
        return ResponseEntity.ok(body);
    }

    /**
     * Pone nombre y correo a cada asignación.
     *
     * sales-org guarda a quién sin saber quién es —es un servicio de estructura, no un
     * directorio—, así que la lista llega con puros UUID. Un tablero de "quién está en esta
     * unidad" que muestra 52 identificadores no responde la pregunta que le hicieron.
     *
     * El directorio se pide <b>una vez</b> y se indexa en memoria: son decenas de empleados,
     * no miles, y una llamada por asignación convertiría un tablero en 52 peticiones. Si
     * identity no responde, las asignaciones se quedan con su UUID: el roster sigue siendo
     * legible por unidad y sólo se pierde el nombre.
     */
    private void fillAssigneeNames(List<Map<String, Object>> assignees, HttpServletRequest http) {
        if (assignees.isEmpty()) return;
        String token = StaffAuthController.bearerToken(http);

        // El nombre primero, por la vía que cualquier empleado puede usar. Antes esto salía del
        // directorio completo —que es de ADMIN—, así que a un gerente comercial le fallaba en
        // silencio y su propio equipo aparecía como una lista de UUIDs.
        Map<String, String> names = new LinkedHashMap<>();
        try {
            safe(identityClient.listStaffNames(token))
                    .forEach(e -> names.put(e.id().toString(), e.name()));
        } catch (Exception ex) {
            log.warn("Sin directorio de nombres: {}", ex.getMessage());
        }

        // El resto —correo, roles, estatus— sólo para quien puede verlo. No es degradación: es
        // que un roster necesita nombres y sólo la administración de personal necesita lo demás.
        Map<String, IdentityClient.StaffUserResponse> detalle = Map.of();
        try {
            detalle = safe(identityClient.listStaff(token, null, null))
                    .stream().collect(Collectors.toMap(u -> u.staffUserId().toString(), u -> u, (a, b) -> a));
        } catch (Exception ex) {
            log.debug("El solicitante no tiene el directorio completo: {}", ex.getMessage());
        }

        for (Map<String, Object> a : assignees) {
            Object id = a.get("assigneeId");
            if (id == null) continue;
            String name = names.get(id.toString());
            if (name != null) a.put("name", name);
            IdentityClient.StaffUserResponse staff = detalle.get(id.toString());
            if (staff != null) {
                a.putIfAbsent("name", staff.fullName());
                a.put("email", staff.email());
                a.put("roles", staff.roles());
                a.put("status", staff.status());
            }
        }
    }

    /**
     * La cartera que no cuelga de nadie.
     *
     * <p>Existe porque el árbol comercial y el panorama de cartera daban cifras distintas del
     * mismo negocio, y no había en la pantalla nada que explicara por qué. La diferencia es real:
     * son créditos vivos cuyo cliente no tiene ejecutivo asignado, o lo tiene sin adscripción en
     * sales-org. No es un error de suma, es <b>cartera sin dueño</b> — nadie la visita, nadie
     * cobra por ella y no aparece en la meta de ninguna sucursal.
     *
     * <p>Sólo tiene sentido en la raíz: para una zona, «lo que no cuelga de aquí» es simplemente
     * el resto de la red, y llamarlo huérfano sería mentir. Se devuelve {@code null} en el resto
     * de los nodos para que la pantalla no tenga que decidirlo.
     *
     * <p>Cuesta una consulta agregada, no un recorrido: el total de la red ya lo calcula la base
     * en una pasada para el tablero general.
     */
    private Map<String, Object> carteraSinTitular(UUID unitId, Map<String, Object> enElArbol) {
        Map<String, Object> unidad = salesOrgClient.getUnit(unitId);
        if (unidad == null || unidad.get("parentUnitId") != null) return null;

        var s = creditPortfolioClient.summary();
        if (s == null) return null;

        BigDecimal red = nz(s.principalBalance());
        BigDecimal arbol = enElArbol.get("principal") instanceof BigDecimal b ? b : BigDecimal.ZERO;
        long cuentasRed = s.activeAccounts();
        long cuentasArbol = enElArbol.get("accounts") instanceof Number n ? n.longValue() : 0L;

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("networkAccounts", cuentasRed);
        m.put("networkPrincipal", red);
        // Nunca negativo: si el rollup superara al agregado sería un desajuste de estatus entre
        // los dos servicios, y mostrarlo en rojo como "cartera sin dueño" señalaría al lugar
        // equivocado. Cero dice "no falta nada" sin inventar una cifra.
        m.put("accounts", Math.max(cuentasRed - cuentasArbol, 0));
        m.put("principal", red.subtract(arbol).max(BigDecimal.ZERO));
        return m;
    }

    @GetMapping("/dashboard/stats")
    @Operation(summary = "Distribución de la cartera por dimensión",
            description = "groupBy = status | productType | dpdBucket. La agrega la base.")
    ResponseEntity<List<Map<String, Object>>> stats(
            @RequestParam(defaultValue = "status") String groupBy) {
        log.info("GET /dashboard/stats groupBy={}", groupBy);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (var s : safe(creditPortfolioClient.stats(groupBy))) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", s.key());
            row.put("count", s.count());
            row.put("principal", s.principal());
            rows.add(row);
        }
        return ResponseEntity.ok(rows);
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }

    private static <T> List<T> safe(List<T> xs) { return xs == null ? List.of() : xs; }
}

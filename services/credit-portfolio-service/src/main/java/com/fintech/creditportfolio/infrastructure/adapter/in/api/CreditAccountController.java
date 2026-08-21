package com.fintech.creditportfolio.infrastructure.adapter.in.api;

import com.fintech.creditportfolio.application.port.in.FindCreditAccountUseCase;
import com.fintech.creditportfolio.application.port.out.CreditAccountRepository;
import com.fintech.creditportfolio.application.port.out.OriginUnitStat;
import com.fintech.creditportfolio.application.port.out.PortfolioStat;
import com.fintech.creditportfolio.application.port.out.PortfolioSummary;
import com.fintech.creditportfolio.application.port.out.DispositionRepository;
import com.fintech.creditportfolio.application.port.out.InstallmentRepository;
import com.fintech.creditportfolio.domain.CreditAccount;
import com.fintech.creditportfolio.domain.CreditAccountStatus;
import com.fintech.creditportfolio.domain.Disposition;
import com.fintech.creditportfolio.domain.Installment;
import com.fintech.creditportfolio.domain.PaymentPlanSummary;
import com.fintech.creditportfolio.infrastructure.adapter.in.api.dto.CreditAccountResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/portfolio/accounts")
@Tag(name = "Credit Accounts", description = "Live credit account management")
class CreditAccountController {

    /** Tope de ids por lote: el /batch hidrata una página, no toda la cartera. */
    private static final int MAX_BATCH_IDS = 200;

    /** Campos por los que se puede ordenar (whitelist: el `sort` viene del cliente). */
    private static final Set<String> SORTABLE =
            Set.of("createdAt", "daysDelinquent", "principalBalance", "status", "productType", "activatedAt");

    /** Dimensiones válidas de agregación para /stats. */
    private static final Set<String> STAT_GROUPINGS = Set.of("status", "productType", "dpdBucket");

    private final FindCreditAccountUseCase findUseCase;
    private final CreditAccountRepository creditAccountRepository;
    private final DispositionRepository dispositionRepository;
    private final InstallmentRepository installmentRepository;

    CreditAccountController(FindCreditAccountUseCase findUseCase,
                             CreditAccountRepository creditAccountRepository,
                             DispositionRepository dispositionRepository,
                             InstallmentRepository installmentRepository) {
        this.findUseCase              = findUseCase;
        this.creditAccountRepository  = creditAccountRepository;
        this.dispositionRepository    = dispositionRepository;
        this.installmentRepository    = installmentRepository;
    }

    /**
     * Sella la sucursal de origen de un crédito ya existente.
     *
     * <p>Existe para la <b>reconciliación</b>, no para la operación: los créditos anteriores a que
     * cartera empezara a sellar la sucursal quedaron sin ella, y hay que resolverlos una vez
     * —ejecutivo asignado → sucursal en sales-org— y congelarlos. La orquestación vive en un script,
     * como el resto de las reconciliaciones de este repo, en vez de meterle a cartera un cliente HTTP
     * hacia otro dominio para un caso que ocurre una sola vez.
     *
     * <p><b>Es idempotente y no sobrescribe.</b> Un crédito que ya tiene sucursal se queda con la
     * suya: reasignar la cartera no puede reescribir a qué rama se le atribuyó el ingreso pasado.
     */
    @PutMapping("/{creditAccountId}/origin-unit")
    @Operation(summary = "Sella la sucursal de origen (reconciliación; no sobrescribe la ya sellada)")
    ResponseEntity<CreditAccountResponse> sealOriginUnit(@PathVariable UUID creditAccountId,
                                                          @RequestBody SealOriginUnitRequest request) {
        CreditAccount account = findUseCase.getById(creditAccountId);
        account.sealOriginUnit(request.originUnitCode());
        creditAccountRepository.save(account);
        return ResponseEntity.ok(withPlan(account));
    }

    public record SealOriginUnitRequest(String originUnitCode) {}

    /**
     * El calendario de una disposición.
     *
     * <p>En una revolvente el calendario no cuelga de la cuenta sino de cada disposición, así que
     * ésta es la ruta que explica la deuda de una línea: qué se colocó, a quién, a cuántos meses y
     * qué falta por pagar. Sin ella, la única explicación de un DPD en una línea sería «lo dice el
     * número», que no es una explicación.
     */
    @GetMapping("/{creditAccountId}/dispositions/{dispositionId}/schedule")
    @Operation(summary = "Calendario de amortización de una disposición")
    List<Installment> dispositionSchedule(@PathVariable UUID creditAccountId,
                                           @PathVariable UUID dispositionId) {
        return installmentRepository.findByScheduleIdOrdered(dispositionId);
    }

    @GetMapping("/{creditAccountId}")
    @Operation(summary = "Get credit account by id")
    ResponseEntity<CreditAccountResponse> getById(@PathVariable UUID creditAccountId) {
        return ResponseEntity.ok(withPlan(findUseCase.getById(creditAccountId)));
    }

    @GetMapping
    @Operation(summary = "List credit accounts for a party (BFF use: ?partyId={partyId})")
    List<CreditAccountResponse> listByParty(@RequestParam UUID partyId) {
        return creditAccountRepository.findByObligorPartyId(partyId)
                .stream()
                .map(this::withPlan)
                .toList();
    }

    /**
     * La cuenta con el avance de su plan de pagos.
     *
     * <p>Se resuelve aquí y no en el canal: el calendario vive en este servicio
     * y calcularlo del lado del cliente se desincroniza en cuanto hay un pago
     * parcial o una reestructura. Una consulta más por cuenta a cambio de que
     * el canal no tenga que descargar el calendario completo para contar
     * cuántas mensualidades van.
     */

    /**
     * El avance del plan de una página de cuentas, mezclando las dos formas.
     *
     * <p>Un amortizable guarda su calendario bajo el id de la cuenta; una revolvente, bajo el de
     * cada disposición. Pedir el lote sólo por id de cuenta —lo que se hacía— devolvía vacío para
     * toda revolvente, así que el listado enseñaba «0 de 0» y «a cobrar: 0» en cuentas que sí
     * tenían plan y sí debían. Peor que un error: la ficha mostraba una cosa y la lista otra.
     *
     * <p>Sigue siendo acotado: dos consultas por página, no una por cuenta.
     */
    private Map<UUID, PaymentPlanSummary> planesDe(List<CreditAccount> accounts) {
        List<UUID> amortizables = accounts.stream()
                .filter(a -> !a.isRevolving()).map(CreditAccount::getCreditAccountId).toList();
        List<UUID> revolventes = accounts.stream()
                .filter(CreditAccount::isRevolving).map(CreditAccount::getCreditAccountId).toList();

        Map<UUID, PaymentPlanSummary> planes =
                new java.util.HashMap<>(installmentRepository.planSummaries(amortizables));

        if (!revolventes.isEmpty()) {
            // calendario (= disposición) → cuenta a la que pertenece
            Map<UUID, UUID> cuentaDe = dispositionRepository.findByCreditAccountIdIn(revolventes)
                    .stream()
                    .collect(java.util.stream.Collectors.toMap(
                            com.fintech.creditportfolio.domain.Disposition::getDispositionId,
                            com.fintech.creditportfolio.domain.Disposition::getCreditAccountId,
                            (a, b) -> a));

            Map<UUID, List<Installment>> porCuenta = installmentRepository
                    .findByScheduleIds(cuentaDe.keySet()).stream()
                    .filter(i -> cuentaDe.containsKey(i.getScheduleId()))
                    .collect(java.util.stream.Collectors.groupingBy(
                            i -> cuentaDe.get(i.getScheduleId())));

            porCuenta.forEach((cuenta, cuotas) ->
                    planes.put(cuenta, PaymentPlanSummary.fromRevolving(cuotas)));
        }
        return planes;
    }

    private CreditAccountResponse withPlan(CreditAccount account) {
        // Un amortizable comparte scheduleId con la cuenta, por convención. Una revolvente no tiene
        // un calendario sino uno por disposición, así que el avance del plan es el de todas juntas:
        // «va 7 de 36» sobre una línea significa siete cuotas cubiertas de las que debe en total,
        // sumando sus colocaciones. Leer sólo el calendario de la cuenta devolvía vacío y la ficha
        // de una línea salía sin plan de pagos.
        return CreditAccountResponse.from(account,
                planesDe(List.of(account)).getOrDefault(
                        account.getCreditAccountId(), PaymentPlanSummary.empty()));
    }

    @GetMapping("/search")
    @Operation(summary = "Listado paginado de cuentas (backoffice)",
            description = "Filtros opcionales; la paginación, el filtrado y el orden los resuelve la "
                        + "base. El tamaño de página se acota a 100 para que un cliente no pueda pedir "
                        + "la cartera entera en una llamada. `partyIds` filtra a un conjunto de "
                        + "obligados — sirve para hidratar una tabla de otro servicio sin N+1.")
    Page<CreditAccountResponse> search(@RequestParam(required = false) CreditAccountStatus status,
                                        @RequestParam(required = false) String productType,
                                        @RequestParam(required = false) String q,
                                        @RequestParam(required = false) Integer minDaysDelinquent,
                                        @RequestParam(required = false) Integer maxDaysDelinquent,
                                        @RequestParam(required = false) List<UUID> partyIds,
                                        @RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "25") int size,
                                        @RequestParam(defaultValue = "createdAt,desc") String sort) {
        var pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100), parseSort(sort));
        var accounts = creditAccountRepository.search(
                status, productType, q, minDaysDelinquent, maxDaysDelinquent, partyIds, pageable);

        // El avance del plan de toda la página en **una** consulta: pedirlo
        // cuenta por cuenta convertiría una página de 25 en 26 viajes a la base.
        var plans = planesDe(accounts.getContent());

        return accounts.map(a -> CreditAccountResponse.from(a,
                plans.getOrDefault(a.getCreditAccountId(), PaymentPlanSummary.empty())));
    }

    @GetMapping("/batch")
    @Operation(summary = "Hidratación por lote: varias cuentas por id en una sola llamada",
            description = "El endpoint que evita el N+1 cuando otro servicio pinta una tabla de "
                        + "cuentas: se piden los ids distintos de la página de una vez.")
    List<CreditAccountResponse> batch(@RequestParam List<UUID> ids) {
        if (ids.size() > MAX_BATCH_IDS) {
            throw new IllegalArgumentException(
                    "Máximo " + MAX_BATCH_IDS + " ids por lote (recibidos " + ids.size() + ")");
        }
        var accounts = creditAccountRepository.findByCreditAccountIdIn(ids);
        var plans = planesDe(accounts);
        return accounts.stream()
                .map(a -> CreditAccountResponse.from(a,
                        plans.getOrDefault(a.getCreditAccountId(), PaymentPlanSummary.empty())))
                .toList();
    }

    @GetMapping("/stats/by-origin-unit")
    @Operation(summary = "Cartera por unidad de ORIGEN (sellada al activar)",
            description = "Agrupa por `origin_unit_code`: la unidad que colocó el crédito, que no "
                        + "cambia aunque la cartera se reasigne. NO es la unidad del ejecutivo que "
                        + "lleva hoy al cliente — son dos preguntas distintas y pueden dar cifras "
                        + "distintas de la misma cartera. `unitCodes` acota; sin él, todas. "
                        + "Devuelve los tramos IFRS-9 y no una provisión ya calculada: la escala de "
                        + "pérdida esperada vive en el canal, con el resto del tablero.")
    List<OriginUnitStat> statsByOriginUnit(
            @RequestParam(required = false) List<String> unitCodes) {
        return creditAccountRepository.statsByOriginUnit(unitCodes);
    }

    @GetMapping("/stats")
    @Operation(summary = "Distribución de la cartera agrupada por dimensión",
            description = "groupBy = status | productType | dpdBucket. Se agrega en la base.")
    List<PortfolioStat> stats(@RequestParam(defaultValue = "status") String groupBy) {
        // Se valida en el borde (no en el adaptador): un @Repository traduce
        // IllegalArgumentException a InvalidDataAccessApiUsageException y se
        // perdería el 400. Aquí el 400 llega limpio al operador.
        if (!STAT_GROUPINGS.contains(groupBy)) {
            throw new IllegalArgumentException(
                    "groupBy inválido: '" + groupBy + "' (esperado: status | productType | dpdBucket)");
        }
        return creditAccountRepository.stats(groupBy);
    }

    @GetMapping("/summary")
    @Operation(summary = "Agregados de cartera para el tablero",
            description = "Se calculan en la base en una sola pasada.")
    PortfolioSummary summary() {
        return creditAccountRepository.summary();
    }

    @GetMapping("/product-mix")
    @Operation(summary = "Cartera activa agrupada por producto")
    List<com.fintech.creditportfolio.application.port.out.ProductMix> productMix() {
        return creditAccountRepository.productMix();
    }

    @GetMapping("/{creditAccountId}/dispositions")
    @Operation(summary = "List dispositions for an account")
    List<Disposition> getDispositions(@PathVariable UUID creditAccountId) {
        return dispositionRepository.findByCreditAccountId(creditAccountId);
    }

    @GetMapping("/{creditAccountId}/amortization-schedule")
    @Operation(summary = "Get amortisation schedule (installment products only)")
    List<Installment> getSchedule(@PathVariable UUID creditAccountId) {
        CreditAccount account = findUseCase.getById(creditAccountId);
        // Schedule entries share scheduleId = creditAccountId by convention
        return installmentRepository.findByScheduleId(account.getCreditAccountId());
    }

    /**
     * Traduce {@code sort=campo[,asc|desc]} a un {@link Sort} seguro. El campo se
     * valida contra una whitelist —el cliente no ordena por columnas arbitrarias—
     * y por defecto ordena por más reciente.
     */
    private static Sort parseSort(String sort) {
        Sort defaultSort = Sort.by(Sort.Direction.DESC, "createdAt");
        if (sort == null || sort.isBlank()) {
            return defaultSort;
        }
        String[] parts = sort.split(",");
        String field = parts[0].trim();
        if (!SORTABLE.contains(field)) {
            return defaultSort;
        }
        Sort.Direction dir = parts.length > 1 && parts[1].trim().equalsIgnoreCase("asc")
                ? Sort.Direction.ASC
                : Sort.Direction.DESC;
        return Sort.by(dir, field);
    }
}

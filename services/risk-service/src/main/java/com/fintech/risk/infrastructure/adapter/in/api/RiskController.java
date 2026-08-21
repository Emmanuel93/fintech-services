package com.fintech.risk.infrastructure.adapter.in.api;

import com.fintech.risk.application.CreateProvisionPolicyCommand;
import com.fintech.risk.application.port.in.GetProvisionSummaryUseCase;
import com.fintech.risk.application.port.in.GetRiskProfileUseCase;
import com.fintech.risk.application.port.in.ProvisionPolicyUseCase;
import com.fintech.risk.domain.DelinquencyBucket;
import com.fintech.risk.domain.Ifrs9Stage;
import com.fintech.risk.domain.RiskProfileStatus;
import com.fintech.risk.infrastructure.adapter.in.api.dto.CreateProvisionPolicyRequest;
import com.fintech.risk.infrastructure.adapter.in.api.dto.ProvisionPolicyResponse;
import com.fintech.risk.infrastructure.adapter.in.api.dto.ProvisionSummaryResponse;
import com.fintech.risk.infrastructure.adapter.in.api.dto.RiskProfileResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/risk")
@Tag(name = "Risk", description = "Clasificación IFRS-9 y estimación preventiva de reservas (EPR/ECL)")
@SecurityRequirement(name = "bearerAuth")
class RiskController {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_BATCH_IDS = 200;

    /** Campos por los que se puede ordenar (whitelist: el `sort` viene del cliente). */
    private static final Set<String> SORTABLE = Set.of(
            "createdAt", "daysDelinquent", "provisionAmount", "ead", "ifrs9Stage",
            "lastCalculatedAt", "stageEnteredAt");

    private final GetRiskProfileUseCase getRiskProfileUseCase;
    private final ProvisionPolicyUseCase provisionPolicyUseCase;
    private final GetProvisionSummaryUseCase getProvisionSummaryUseCase;

    RiskController(GetRiskProfileUseCase getRiskProfileUseCase,
                    ProvisionPolicyUseCase provisionPolicyUseCase,
                    GetProvisionSummaryUseCase getProvisionSummaryUseCase) {
        this.getRiskProfileUseCase       = getRiskProfileUseCase;
        this.provisionPolicyUseCase      = provisionPolicyUseCase;
        this.getProvisionSummaryUseCase  = getProvisionSummaryUseCase;
    }

    @Operation(summary = "Get the current risk profile for a credit account")
    @GetMapping("/accounts/{creditAccountId}")
    ResponseEntity<RiskProfileResponse> getByAccount(@PathVariable UUID creditAccountId) {
        return ResponseEntity.ok(RiskProfileResponse.from(
                getRiskProfileUseCase.getByCreditAccountId(creditAccountId)));
    }

    @Operation(summary = "Listado paginado de perfiles de riesgo (backoffice)",
            description = "partyId es opcional: sin él es una bandeja por población. Filtros por "
                        + "productType, etapa IFRS-9 y estado. Paginación y orden los resuelve la base.")
    @GetMapping("/accounts")
    Page<RiskProfileResponse> list(@RequestParam(required = false) UUID partyId,
                                   @RequestParam(required = false) String productType,
                                   @RequestParam(required = false) Ifrs9Stage stage,
                                   @RequestParam(required = false) RiskProfileStatus status,
                                   @RequestParam(defaultValue = "0") int page,
                                   @RequestParam(defaultValue = "25") int size,
                                   @RequestParam(defaultValue = "createdAt,desc") String sort) {
        Pageable pageable = PageRequest.of(
                Math.max(0, page), Math.min(Math.max(1, size), MAX_PAGE_SIZE), parseSort(sort));
        return getRiskProfileUseCase.search(partyId, productType, stage, status, pageable)
                .map(RiskProfileResponse::from);
    }

    @Operation(summary = "Hidratación por lote: perfiles de riesgo por id de cuenta",
            description = "Evita el N+1 al pintar la etapa/provisión de una tabla de cartera.")
    @GetMapping("/accounts/batch")
    List<RiskProfileResponse> batch(@RequestParam List<UUID> ids) {
        if (ids.size() > MAX_BATCH_IDS) {
            throw new IllegalArgumentException(
                    "Máximo " + MAX_BATCH_IDS + " ids por lote (recibidos " + ids.size() + ")");
        }
        return getRiskProfileUseCase.findByCreditAccountIds(ids).stream()
                .map(RiskProfileResponse::from).toList();
    }

    @Operation(summary = "Current provision rollup by productType/stage (for Finance/Audit and future T4)")
    @GetMapping("/provisions/summary")
    ResponseEntity<ProvisionSummaryResponse> provisionSummary() {
        return ResponseEntity.ok(ProvisionSummaryResponse.from(getProvisionSummaryUseCase.summary()));
    }

    @Operation(summary = "List the ACTIVE provision policies (transparency — why a reserve is X%)")
    @GetMapping("/provision-policies")
    ResponseEntity<List<ProvisionPolicyResponse>> listPolicies() {
        return ResponseEntity.ok(provisionPolicyUseCase.listActive()
                .stream().map(ProvisionPolicyResponse::from).toList());
    }

    @Operation(summary = "Get the ACTIVE provision policy for a productType")
    @GetMapping("/provision-policies/{productType}")
    ResponseEntity<ProvisionPolicyResponse> getPolicy(@PathVariable String productType) {
        return ResponseEntity.ok(ProvisionPolicyResponse.from(
                provisionPolicyUseCase.getActiveByProductType(productType)));
    }

    @Operation(summary = "Create/version a provision policy (risk team)")
    @PostMapping("/provision-policies")
    @ResponseStatus(HttpStatus.CREATED)
    ProvisionPolicyResponse createPolicy(@Valid @RequestBody CreateProvisionPolicyRequest req) {
        Map<DelinquencyBucket, BigDecimal> rates = new EnumMap<>(DelinquencyBucket.class);
        req.rates().forEach((bucket, rate) -> rates.put(DelinquencyBucket.valueOf(bucket), rate));
        return ProvisionPolicyResponse.from(
                provisionPolicyUseCase.create(new CreateProvisionPolicyCommand(req.productType(), rates)));
    }

    /**
     * Traduce {@code sort=campo[,asc|desc]} a un {@link Sort} seguro. El campo se
     * valida contra una whitelist y por defecto ordena por más reciente.
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

package com.fintech.salesorg.infrastructure.adapter.in.api;

import com.fintech.salesorg.application.port.in.CreateOrgUnitCommand;
import com.fintech.salesorg.application.port.in.OrgUnitUseCase;
import com.fintech.salesorg.infrastructure.adapter.in.api.dto.CreateOrgUnitRequest;
import com.fintech.salesorg.infrastructure.adapter.in.api.dto.OrgUnitResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * El árbol de unidades comerciales. El alta calcula el path LTREE desde el padre; el subárbol de una
 * unidad (su alcance) se resuelve en una sola consulta indexada.
 */
@RestController
@RequestMapping("/api/v1/sales-org/units")
@Tag(name = "Sales Org — Units", description = "Árbol de unidades de la red comercial")
class OrgUnitController {

    private static final Logger log = LoggerFactory.getLogger(OrgUnitController.class);

    private final OrgUnitUseCase orgUnitUseCase;
    private final com.fintech.salesorg.application.port.out.OrgUnitRepository orgUnitRepository;

    OrgUnitController(OrgUnitUseCase orgUnitUseCase,
                      com.fintech.salesorg.application.port.out.OrgUnitRepository orgUnitRepository) {
        this.orgUnitUseCase   = orgUnitUseCase;
        this.orgUnitRepository = orgUnitRepository;
    }

    @GetMapping
    @Operation(summary = "Todas las unidades")
    List<OrgUnitResponse> list() {
        return orgUnitUseCase.list().stream().map(OrgUnitResponse::from).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Crear una unidad",
            description = "Sin `parentUnitId` es una unidad raíz (nivel de profundidad 0). El path se "
                        + "calcula desde el padre.")
    OrgUnitResponse create(@Valid @RequestBody CreateOrgUnitRequest request,
                           @RequestHeader(value = "X-User-Id", required = false) String staffUserId) {
        log.info("POST /sales-org/units code={} parent={} by={}",
                request.code(), request.parentUnitId(), staffUserId);
        return OrgUnitResponse.from(orgUnitUseCase.create(new CreateOrgUnitCommand(
                request.levelId(), request.parentUnitId(), request.code(), request.name(),
                request.partyRef(), staffUserId)));
    }

    @GetMapping("/{unitId}")
    @Operation(summary = "Detalle de una unidad")
    OrgUnitResponse get(@PathVariable UUID unitId) {
        return OrgUnitResponse.from(orgUnitUseCase.get(unitId));
    }

    @PutMapping("/{unitId}/parent")
    @Operation(summary = "Colgar la unidad de otro padre",
            description = "Mueve la rama completa: la unidad conserva su id, su código y sus "
                        + "asignaciones, y se reescribe la ruta de todos sus descendientes.")
    OrgUnitResponse move(@PathVariable UUID unitId,
                         @RequestBody MoveUnitRequest request,
                         @RequestHeader(value = "X-User-Id", required = false) String staffUserId) {
        return OrgUnitResponse.from(orgUnitUseCase.move(unitId, request.parentUnitId()));
    }

    /** El nuevo padre. Nulo sólo vale para el nivel raíz. */
    public record MoveUnitRequest(UUID parentUnitId) {}

    /**
     * El IVA que aplica al colocar en esta unidad, ya resuelto por herencia.
     *
     * <p>Lo consulta quien origina un crédito, para congelarlo en la cuenta: la tasa vigente el día
     * que se colocó es la que rige su vida entera, y una reforma posterior no puede reescribir el
     * plan de pagos que el cliente firmó.
     */
    /**
     * Fija el IVA de una unidad. Lo hereda todo su subárbol.
     *
     * <p>Se declara en el nodo más alto que comparta tasa —la región fronteriza— y no sucursal por
     * sucursal: así la próxima plaza que se abra nace con la tasa correcta sin que nadie tenga que
     * acordarse de ponérsela.
     *
     * <p>{@code null} la borra y devuelve la unidad a heredar de su padre.
     */
    @PutMapping("/by-code/{code}/vat-rate")
    java.util.Map<String, Object> setVatRate(@PathVariable String code,
                                             @RequestParam(required = false) java.math.BigDecimal rate) {
        var unidad = orgUnitRepository.findByCode(code)
                .orElseThrow(() -> new IllegalArgumentException("Unidad no encontrada: " + code));
        unidad.setVatRate(rate);
        orgUnitRepository.save(unidad);
        log.info("IVA de la unidad {} fijado en {}", code, rate);
        java.util.Map<String, Object> r = new java.util.LinkedHashMap<>();
        r.put("code", code);
        r.put("vatRate", rate);
        return r;
    }

    @GetMapping("/by-code/{code}/vat-rate")
    java.util.Map<String, Object> vatRate(@PathVariable String code) {
        java.math.BigDecimal tasa = orgUnitRepository.findEffectiveVatRate(code);
        java.util.Map<String, Object> r = new java.util.LinkedHashMap<>();
        r.put("code", code);
        // Sin ancestro con tasa declarada devuelve null, no cero: cero es «exento», que es una
        // decisión fiscal distinta de «aquí no se configuró nada». Quien pregunta aplica su default.
        r.put("vatRate", tasa);
        r.put("configured", tasa != null);
        return r;
    }

    @GetMapping("/{unitId}/children")
    @Operation(summary = "Hijos directos de una unidad")
    List<OrgUnitResponse> children(@PathVariable UUID unitId) {
        return orgUnitUseCase.children(unitId).stream().map(OrgUnitResponse::from).toList();
    }

    @GetMapping("/{unitId}/subtree")
    @Operation(summary = "La unidad y todo su subárbol (su alcance) — una sola consulta LTREE")
    List<OrgUnitResponse> subtree(@PathVariable UUID unitId) {
        return orgUnitUseCase.subtree(unitId).stream().map(OrgUnitResponse::from).toList();
    }

    @GetMapping("/{unitId}/distributors")
    @Operation(summary = "partyIds de los distribuidores en el subárbol (para acotar la cartera)")
    List<UUID> distributors(@PathVariable UUID unitId) {
        return orgUnitUseCase.distributorsInSubtree(unitId);
    }

    @GetMapping("/{unitId}/executives")
    @Operation(summary = "staffUserIds de los ejecutivos en el subárbol")
    List<UUID> executives(@PathVariable UUID unitId) {
        return orgUnitUseCase.executivesInSubtree(unitId);
    }
}

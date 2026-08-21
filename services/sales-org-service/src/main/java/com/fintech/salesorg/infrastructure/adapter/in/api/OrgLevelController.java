package com.fintech.salesorg.infrastructure.adapter.in.api;

import com.fintech.salesorg.application.port.in.OrgLevelUseCase;
import com.fintech.salesorg.domain.OrgLevel;
import com.fintech.salesorg.infrastructure.adapter.in.api.dto.CreateOrgLevelRequest;
import com.fintech.salesorg.infrastructure.adapter.in.api.dto.OrgLevelResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * La escalera de niveles de la red comercial. Configurable: se anexan o insertan niveles sin tocar
 * código (la "matriz de escalado" es dato, no un enum).
 */
@RestController
@RequestMapping("/api/v1/sales-org/levels")
@Tag(name = "Sales Org — Levels", description = "Escalera de niveles configurable")
class OrgLevelController {

    private static final Logger log = LoggerFactory.getLogger(OrgLevelController.class);

    private final OrgLevelUseCase orgLevelUseCase;

    OrgLevelController(OrgLevelUseCase orgLevelUseCase) {
        this.orgLevelUseCase = orgLevelUseCase;
    }

    @GetMapping
    @Operation(summary = "Escalera de niveles, de la raíz hacia abajo")
    List<OrgLevelResponse> list() {
        return orgLevelUseCase.list().stream().map(OrgLevelResponse::from).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Crear un nivel",
            description = "Sin `depth` se anexa al fondo; con `depth` se inserta ahí corriendo los "
                        + "niveles iguales o más profundos (solo si aún no tienen unidades).")
    OrgLevelResponse create(@Valid @RequestBody CreateOrgLevelRequest request) {
        log.info("POST /sales-org/levels code={} depth={}", request.code(), request.depth());
        OrgLevel level = request.depth() == null
                ? orgLevelUseCase.append(request.code(), request.name())
                : orgLevelUseCase.insertAt(request.depth(), request.code(), request.name());
        return OrgLevelResponse.from(level);
    }
}

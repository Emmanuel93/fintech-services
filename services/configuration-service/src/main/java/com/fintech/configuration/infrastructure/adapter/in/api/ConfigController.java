package com.fintech.configuration.infrastructure.adapter.in.api;

import com.fintech.configuration.application.CreateConfigParameterCommand;
import com.fintech.configuration.application.port.in.*;
import com.fintech.configuration.domain.ConfigParameterNotFoundException;
import com.fintech.configuration.infrastructure.adapter.in.api.dto.ConfigParameterResponse;
import com.fintech.configuration.infrastructure.adapter.in.api.dto.CreateConfigParameterRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/config")
@Tag(name = "Configuration", description = "Business parameter management with maker-checker approval")
@SecurityRequirement(name = "bearerAuth")
class ConfigController {

    private final GetConfigParameterUseCase getConfigUseCase;
    private final CreateConfigParameterUseCase createConfigUseCase;
    private final ApproveConfigParameterUseCase approveConfigUseCase;
    private final GetConfigHistoryUseCase getHistoryUseCase;

    ConfigController(GetConfigParameterUseCase getConfigUseCase,
                     CreateConfigParameterUseCase createConfigUseCase,
                     ApproveConfigParameterUseCase approveConfigUseCase,
                     GetConfigHistoryUseCase getHistoryUseCase) {
        this.getConfigUseCase = getConfigUseCase;
        this.createConfigUseCase = createConfigUseCase;
        this.approveConfigUseCase = approveConfigUseCase;
        this.getHistoryUseCase = getHistoryUseCase;
    }

    @Operation(summary = "Get active config parameter by key")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Parameter found"),
        @ApiResponse(responseCode = "404", description = "No active parameter for key")
    })
    @GetMapping("/{key}")
    ResponseEntity<ConfigParameterResponse> getActive(
            @PathVariable String key,
            @RequestParam(required = false) String productType,
            @RequestParam(required = false) String channelType) {
        return getConfigUseCase.getActive(key, productType, channelType)
                .map(ConfigParameterResponse::from)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new ConfigParameterNotFoundException(key));
    }

    @Operation(summary = "Create config parameter (maker)")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Parameter created in PENDING_APPROVAL status"),
        @ApiResponse(responseCode = "400", description = "Invalid input"),
        @ApiResponse(responseCode = "409", description = "Duplicate active parameter exists")
    })
    @PostMapping
    ResponseEntity<ConfigParameterResponse> create(
            @Valid @RequestBody CreateConfigParameterRequest req,
            Authentication auth) {
        UUID createdBy = UUID.fromString(auth.getName());
        var cmd = new CreateConfigParameterCommand(
                req.paramKey(), req.value(),
                req.productType(), req.channelType(),
                req.effectiveDate(), createdBy);
        var param = createConfigUseCase.create(cmd);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{key}").buildAndExpand(param.getParamKey()).toUri();
        return ResponseEntity.created(location).body(ConfigParameterResponse.from(param));
    }

    @Operation(summary = "Approve config parameter (checker)")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Parameter approved and ACTIVE"),
        @ApiResponse(responseCode = "404", description = "Parameter not found"),
        @ApiResponse(responseCode = "422", description = "Invalid state transition")
    })
    @PutMapping("/{id}/approve")
    ResponseEntity<ConfigParameterResponse> approve(
            @PathVariable UUID id,
            Authentication auth) {
        UUID approverUuid = UUID.fromString(auth.getName());
        var param = approveConfigUseCase.approve(id, approverUuid);
        return ResponseEntity.ok(ConfigParameterResponse.from(param));
    }

    @Operation(summary = "Get all versions of a config parameter by key")
    @ApiResponse(responseCode = "200", description = "Version history (may be empty)")
    @GetMapping("/{key}/history")
    ResponseEntity<List<ConfigParameterResponse>> getHistory(@PathVariable String key) {
        List<ConfigParameterResponse> history = getHistoryUseCase.getHistory(key)
                .stream().map(ConfigParameterResponse::from).toList();
        return ResponseEntity.ok(history);
    }
}

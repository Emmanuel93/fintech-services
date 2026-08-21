package com.fintech.channels.infrastructure.adapter.in.api;

import com.fintech.channels.application.service.IntentService;
import com.fintech.channels.domain.IntentType;
import com.fintech.channels.infrastructure.adapter.in.api.dto.CaptureIntentRequest;
import com.fintech.channels.infrastructure.adapter.in.api.dto.IntentResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/sessions/{sessionId}/intents")
@Tag(name = "Intents", description = "Customer intent capture and routing")
class IntentController {

    private final IntentService intentService;

    IntentController(IntentService intentService) {
        this.intentService = intentService;
    }

    @PostMapping
    @Operation(summary = "Capture a customer intent within a session")
    ResponseEntity<IntentResponse> captureIntent(@PathVariable UUID sessionId,
                                                  @Valid @RequestBody CaptureIntentRequest req) {
        IntentType type = IntentType.valueOf(req.intentType());
        var intent = intentService.captureIntent(
                sessionId, type, req.productTypeHint(), req.requestedAmount(), req.promoterCode());
        return ResponseEntity.status(HttpStatus.CREATED).body(IntentResponse.from(intent));
    }

    @PutMapping("/{intentId}/route")
    @Operation(summary = "Route intent to target domain — emits ApplicationStarted if credit origination")
    ResponseEntity<IntentResponse> routeIntent(@PathVariable UUID sessionId,
                                                @PathVariable UUID intentId,
                                                HttpServletRequest httpRequest) {
        String userId = httpRequest.getHeader("X-User-Id");
        var intent = intentService.routeIntent(sessionId, intentId, userId);
        return ResponseEntity.ok(IntentResponse.from(intent));
    }

    @PutMapping("/{intentId}/abandon")
    @Operation(summary = "Manually abandon an intent")
    ResponseEntity<IntentResponse> abandonIntent(@PathVariable UUID sessionId,
                                                  @PathVariable UUID intentId,
                                                  @RequestBody(required = false) Map<String, String> body) {
        String reason = body != null ? body.get("reason") : null;
        var intent = intentService.abandonIntent(sessionId, intentId, reason);
        return ResponseEntity.ok(IntentResponse.from(intent));
    }
}

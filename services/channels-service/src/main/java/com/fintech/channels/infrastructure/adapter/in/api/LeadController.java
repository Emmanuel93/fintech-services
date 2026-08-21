package com.fintech.channels.infrastructure.adapter.in.api;

import com.fintech.channels.application.service.LeadService;
import com.fintech.channels.domain.IntentType;
import com.fintech.channels.infrastructure.adapter.in.api.dto.CreateLeadRequest;
import com.fintech.channels.infrastructure.adapter.in.api.dto.LeadResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/leads")
@Tag(name = "Leads", description = "Lead capture for field promoters and digital channels")
class LeadController {

    private final LeadService leadService;

    LeadController(LeadService leadService) {
        this.leadService = leadService;
    }

    @PostMapping
    @Operation(summary = "Register a lead prospect")
    ResponseEntity<LeadResponse> createLead(@Valid @RequestBody CreateLeadRequest req) {
        IntentType type = IntentType.valueOf(req.intentType());
        var lead = leadService.createLead(
                req.channelId(), type,
                req.firstName(), req.lastName1(),
                req.phone(), req.email(),
                req.promoterPartyId());
        return ResponseEntity.status(HttpStatus.CREATED).body(LeadResponse.from(lead));
    }

    @GetMapping("/{leadId}")
    @Operation(summary = "Get lead by ID")
    ResponseEntity<LeadResponse> getLead(@PathVariable UUID leadId) {
        return ResponseEntity.ok(LeadResponse.from(leadService.getLead(leadId)));
    }

    @PutMapping("/{leadId}/convert")
    @Operation(summary = "Convert lead when prospect completes party onboarding")
    ResponseEntity<LeadResponse> convertLead(@PathVariable UUID leadId,
                                              @RequestBody Map<String, String> body) {
        UUID convertedPartyId = UUID.fromString(body.get("convertedPartyId"));
        return ResponseEntity.ok(LeadResponse.from(leadService.convertLead(leadId, convertedPartyId)));
    }
}

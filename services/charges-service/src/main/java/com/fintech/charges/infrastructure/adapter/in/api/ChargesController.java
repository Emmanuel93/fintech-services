package com.fintech.charges.infrastructure.adapter.in.api;

import com.fintech.charges.application.port.out.AccrualScheduleRepository;
import com.fintech.charges.application.port.out.ChargeRecordRepository;
import com.fintech.charges.application.service.BalanceSnapshotService;
import com.fintech.charges.application.service.ChargeReversalService;
import com.fintech.charges.domain.AccrualScheduleNotFoundException;
import com.fintech.charges.infrastructure.adapter.in.api.dto.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/charges")
@Tag(name = "Charges", description = "Charge records and accrual schedule management")
class ChargesController {

    private final ChargeReversalService reversalService;
    private final ChargeRecordRepository chargeRecordRepository;
    private final AccrualScheduleRepository scheduleRepository;
    private final BalanceSnapshotService snapshotService;

    ChargesController(ChargeReversalService reversalService,
                       ChargeRecordRepository chargeRecordRepository,
                       AccrualScheduleRepository scheduleRepository,
                       BalanceSnapshotService snapshotService) {
        this.reversalService        = reversalService;
        this.chargeRecordRepository = chargeRecordRepository;
        this.scheduleRepository     = scheduleRepository;
        this.snapshotService        = snapshotService;
    }

    @GetMapping("/accounts/{creditAccountId}")
    @Operation(summary = "List charge records for a credit account")
    @ApiResponse(responseCode = "200", description = "Charge records returned")
    ResponseEntity<List<ChargeRecordResponse>> getCharges(@PathVariable UUID creditAccountId) {
        List<ChargeRecordResponse> charges = chargeRecordRepository
                .findAllByCreditAccountIdOrderByAccrualDateDesc(creditAccountId)
                .stream()
                .map(ChargeRecordResponse::from)
                .toList();
        return ResponseEntity.ok(charges);
    }

    @GetMapping("/accounts/{creditAccountId}/schedule")
    @Operation(summary = "Get accrual schedule for a credit account")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Schedule returned"),
        @ApiResponse(responseCode = "404", description = "Schedule not found")
    })
    ResponseEntity<AccrualScheduleResponse> getSchedule(@PathVariable UUID creditAccountId) {
        return scheduleRepository.findByCreditAccountId(creditAccountId)
                .map(AccrualScheduleResponse::from)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new AccrualScheduleNotFoundException(creditAccountId.toString()));
    }

    @GetMapping("/accounts/{creditAccountId}/balance")
    @Operation(summary = "Get local balance snapshot for a credit account")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Balance snapshot returned"),
        @ApiResponse(responseCode = "404", description = "Snapshot not found")
    })
    ResponseEntity<BalanceSnapshotResponse> getBalance(@PathVariable UUID creditAccountId) {
        return snapshotService.findByCreditAccountId(creditAccountId)
                .map(BalanceSnapshotResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{chargeId}/reverse")
    @Operation(summary = "Reverse an applied charge (technical correction)")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Charge reversed"),
        @ApiResponse(responseCode = "404", description = "Charge not found"),
        @ApiResponse(responseCode = "422", description = "Charge not in APPLIED state")
    })
    @SecurityRequirement(name = "bearerAuth")
    ResponseEntity<ChargeRecordResponse> reverse(@PathVariable UUID chargeId,
                                                  @Valid @RequestBody ReverseChargeRequest req) {
        return ResponseEntity.ok(ChargeRecordResponse.from(
                reversalService.reverse(chargeId, req.reason())));
    }

    @PostMapping("/{chargeId}/waive")
    @Operation(summary = "Waive an applied charge (condonation)")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Charge waived"),
        @ApiResponse(responseCode = "404", description = "Charge not found"),
        @ApiResponse(responseCode = "422", description = "Charge not in APPLIED state")
    })
    @SecurityRequirement(name = "bearerAuth")
    ResponseEntity<ChargeRecordResponse> waive(@PathVariable UUID chargeId,
                                                @Valid @RequestBody WaiveChargeRequest req) {
        return ResponseEntity.ok(ChargeRecordResponse.from(
                reversalService.waive(chargeId, req.waivedBy())));
    }
}

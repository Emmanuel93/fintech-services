package com.fintech.accounting.infrastructure.adapter.in.api;

import com.fintech.accounting.application.LedgerViews.*;
import com.fintech.accounting.application.TrialBalanceRow;
import com.fintech.accounting.application.port.in.GetLedgerUseCase;
import com.fintech.accounting.application.service.BillingService;
import com.fintech.accounting.application.service.LedgerQueryService;
import com.fintech.accounting.infrastructure.adapter.in.api.dto.JournalEntryResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/accounting")
@Tag(name = "Accounting", description = "Pólizas por sucursal y préstamo, balanza, auxiliares y facturación")
@SecurityRequirement(name = "bearerAuth")
class AccountingController {

    private final GetLedgerUseCase getLedgerUseCase;
    private final LedgerQueryService queryService;
    private final BillingService billingService;

    AccountingController(GetLedgerUseCase getLedgerUseCase, LedgerQueryService queryService,
                         BillingService billingService) {
        this.getLedgerUseCase = getLedgerUseCase;
        this.queryService     = queryService;
        this.billingService   = billingService;
    }

    // ── Pólizas ──────────────────────────────────────────────────────────────

    /**
     * El libro de pólizas, paginado <b>en la base</b>.
     *
     * <p>{@code unitCodes} es el alcance ya resuelto: el BFF pregunta el subárbol a sales-org una
     * vez y manda los códigos. Accounting no habla con sales-org — no tiene por qué conocer la forma
     * de la organización para sumar sus propios asientos.
     */
    @Operation(summary = "Libro de pólizas del período, filtrable por sucursal, tipo y préstamo")
    @GetMapping("/vouchers")
    ResponseEntity<VoucherPage> vouchers(
            @RequestParam(required = false) String period,
            @RequestParam(required = false) List<String> unitCodes,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) UUID creditAccountId,
            @RequestParam(required = false) UUID partyId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return ResponseEntity.ok(queryService.vouchers(period, unitCodes, type, creditAccountId,
                partyId, page, Math.min(size, 200)));
    }

    /**
     * El mismo período, agrupado por préstamo. Una fila por crédito con movimiento.
     *
     * <p>La agrupación se hace en SQL: con devengo diario, un mes son miles de pólizas y agruparlas
     * en el cliente obliga a traérselas todas para sumar cien filas.
     */
    @Operation(summary = "Movimiento del período agrupado por préstamo")
    @GetMapping("/loans")
    ResponseEntity<LoanMovementPage> loans(
            @RequestParam(required = false) String period,
            @RequestParam(required = false) List<String> unitCodes,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return ResponseEntity.ok(queryService.loanMovements(period, unitCodes, page, Math.min(size, 200)));
    }

    @Operation(summary = "Una póliza con sus renglones")
    @GetMapping("/vouchers/{voucherId}")
    ResponseEntity<VoucherView> voucher(@PathVariable UUID voucherId) {
        VoucherView v = queryService.voucher(voucherId);
        return v == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(v);
    }

    // ── Resumen ──────────────────────────────────────────────────────────────

    @Operation(summary = "Panorama del período: cuadre, devengo, provisión y desglose por sucursal")
    @GetMapping("/summary")
    ResponseEntity<SummaryView> summary(
            @RequestParam String period,
            @RequestParam(required = false) List<String> unitCodes,
            @RequestParam(defaultValue = "true") boolean includeUnits) {
        return ResponseEntity.ok(queryService.summary(period, unitCodes, includeUnits));
    }

    @Operation(summary = "Movimiento por sucursal del período — agregado en SQL")
    @GetMapping("/units")
    ResponseEntity<List<UnitMovementView>> units(
            @RequestParam String period,
            @RequestParam(required = false) List<String> unitCodes) {
        return ResponseEntity.ok(queryService.unitMovements(period, unitCodes));
    }

    // ── Balanza y auxiliares ─────────────────────────────────────────────────

    @Operation(summary = "Balanza de comprobación del período, acotable por sucursal")
    @GetMapping("/trial-balance")
    ResponseEntity<List<TrialBalanceRowView>> trialBalanceScoped(
            @RequestParam String period,
            @RequestParam(required = false) List<String> unitCodes) {
        return ResponseEntity.ok(queryService.trialBalance(period, unitCodes));
    }

    @Operation(summary = "Ficha contable de un préstamo: saldos acumulados y sus pólizas")
    @GetMapping("/accounts/{creditAccountId}/ledger")
    ResponseEntity<LoanLedgerView> loanLedger(@PathVariable UUID creditAccountId) {
        return ResponseEntity.ok(queryService.loanLedger(creditAccountId));
    }

    @Operation(summary = "Auxiliar contable de un crédito (asientos a nivel préstamo)")
    @GetMapping("/accounts/{creditAccountId}/journal")
    ResponseEntity<List<JournalEntryResponse>> journalByAccount(@PathVariable UUID creditAccountId) {
        return ResponseEntity.ok(getLedgerUseCase.journalByAccount(creditAccountId)
                .stream().map(JournalEntryResponse::from).toList());
    }

    @Operation(summary = "Auxiliar contable de un party (todas sus cuentas)")
    @GetMapping("/parties/{partyId}/journal")
    ResponseEntity<List<JournalEntryResponse>> journalByParty(@PathVariable UUID partyId) {
        return ResponseEntity.ok(getLedgerUseCase.journalByParty(partyId)
                .stream().map(JournalEntryResponse::from).toList());
    }

    /** Se conserva por compatibilidad: `/trial-balance` sin `unitCodes` responde lo mismo. */
    @Operation(summary = "Balanza legada (sin alcance)", deprecated = true)
    @GetMapping("/trial-balance/legacy")
    ResponseEntity<List<TrialBalanceRow>> trialBalanceLegacy(@RequestParam String period) {
        return ResponseEntity.ok(getLedgerUseCase.trialBalance(period));
    }

    // ── Períodos ─────────────────────────────────────────────────────────────

    @Operation(summary = "Períodos contables con su estatus y número de pólizas")
    @GetMapping("/periods")
    ResponseEntity<List<PeriodView>> periods() {
        return ResponseEntity.ok(queryService.periods());
    }

    /**
     * Cierra el período.
     *
     * <p>No rechaza lo que llegue después: un hecho de un mes cerrado se asienta en el primero
     * abierto marcado como extemporáneo. Cerrar es decir «estos números ya se publicaron», no
     * levantar un muro que haga desaparecer hechos.
     */
    @Operation(summary = "Cierra un período contable")
    @PostMapping("/periods/{period}/close")
    ResponseEntity<Map<String, Object>> close(@PathVariable String period) {
        var p = queryService.closePeriod(period);
        return ResponseEntity.ok(Map.of("period", p.getPeriod(), "status", p.getStatus().name()));
    }

    @Operation(summary = "Reabre un período contable")
    @PostMapping("/periods/{period}/reopen")
    ResponseEntity<Map<String, Object>> reopen(@PathVariable String period) {
        var p = queryService.reopenPeriod(period);
        return ResponseEntity.ok(Map.of("period", p.getPeriod(), "status", p.getStatus().name()));
    }

    /**
     * Atribuye a su sucursal las pólizas de un crédito sellado a posteriori.
     *
     * <p>Lo llama la reconciliación del canal justo después de sellar el préstamo en cartera. Sin
     * este paso, un crédito recién sellado seguiría contando bajo «Sin sucursal» hasta su siguiente
     * movimiento contable — que puede ser el mes que entra.
     */
    @Operation(summary = "Atribuye a una sucursal las pólizas ya asentadas de un crédito")
    @PostMapping("/accounts/{creditAccountId}/org-unit")
    ResponseEntity<Map<String, Object>> attributeUnit(@PathVariable UUID creditAccountId,
                                                       @RequestParam String orgUnitCode) {
        int updated = queryService.attributeUnit(creditAccountId, orgUnitCode);
        return ResponseEntity.ok(Map.of("creditAccountId", creditAccountId,
                "orgUnitCode", orgUnitCode, "vouchersUpdated", updated));
    }

    @Operation(summary = "Atribuye sucursal a toda póliza cuyo crédito ya la tenga (barrido)")
    @PostMapping("/org-units/sweep")
    ResponseEntity<Map<String, Object>> sweepUnits() {
        return ResponseEntity.ok(Map.of("vouchersUpdated", queryService.sweepUnits()));
    }

    // ── Facturación ──────────────────────────────────────────────────────────

    @Operation(summary = "Dispara la corrida de facturación consolidada de un período")
    @PostMapping("/billing-runs")
    ResponseEntity<Map<String, Object>> runBilling(@RequestParam String period) {
        int invoices = billingService.runBilling(period);
        return ResponseEntity.ok(Map.of("period", period, "invoicesRequested", invoices));
    }
}

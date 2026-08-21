package com.fintech.beneficiary.infrastructure.adapter.in.api;

import com.fintech.beneficiary.application.port.out.PlacementRepository;
import com.fintech.beneficiary.application.service.PlacementOrchestrator;
import com.fintech.beneficiary.domain.Placement;
import com.fintech.beneficiary.domain.PlacementStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Las dos vistas del home del distribuidor: su línea y su directorio de beneficiarios.
 *
 * <p>El directorio no es la lista de colocaciones. Una persona tiene <i>n</i> colocaciones a lo
 * largo del tiempo, y el diseño las separa en dos pestañas justo por eso: en una se gestiona la
 * operación viva, en la otra se consulta a quién le has prestado y cómo te ha respondido.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Distribuidor", description = "Línea y beneficiarios")
class DistributorQueryController {

    private final PlacementOrchestrator orchestrator;
    private final PlacementRepository placements;

    DistributorQueryController(PlacementOrchestrator orchestrator, PlacementRepository placements) {
        this.orchestrator = orchestrator;
        this.placements   = placements;
    }

    @Operation(summary = "Mi línea de distribuidor",
               description = "Responde 200 con authorized=0 cuando el titular no tiene línea. "
                           + "No es un error: es quien pidió crédito para sí.")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/distributor/line-summary")
    ResponseEntity<LineSummaryResponse> lineSummary() {
        var s = orchestrator.lineSummary(currentPartyId());
        return ResponseEntity.ok(new LineSummaryResponse(
                s.authorized(), s.placed(), s.available(), s.toCollect(),
                s.dueToKredius(), s.commissionAccrued(), s.nextDueDate(), s.daysPastDue()));
    }

    @Operation(summary = "Mis beneficiarios",
               description = "El directorio: una fila por persona, con su historial contigo.")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/beneficiaries")
    ResponseEntity<BeneficiaryListResponse> beneficiaries() {
        UUID distributorPartyId = currentPartyId();

        // Se agrupa por teléfono y no por partyId: antes de cerrar su KYC la beneficiaria todavía
        // no tiene Party, y dejarla fuera del directorio hasta entonces escondería justo a la que
        // está en proceso — que es de la que el distribuidor quiere saber.
        Map<String, List<Placement>> byPerson = new LinkedHashMap<>();
        for (Placement p : placements.findByDistributorPartyIdOrderByCreatedAtDesc(distributorPartyId)) {
            if (p.getStatus() == PlacementStatus.CANCELLED || p.getStatus() == PlacementStatus.EXPIRED) {
                continue;
            }
            byPerson.computeIfAbsent(p.getBeneficiaryPhone(), k -> new ArrayList<>()).add(p);
        }

        List<BeneficiaryResponse> rows = new ArrayList<>();
        byPerson.forEach((phone, list) -> rows.add(toRow(list)));
        rows.sort(Comparator.comparing(BeneficiaryResponse::fullName));
        return ResponseEntity.ok(new BeneficiaryListResponse(rows));
    }

    private BeneficiaryResponse toRow(List<Placement> history) {
        Placement latest = history.getFirst();

        BigDecimal lent = history.stream()
                .filter(p -> p.getStatus().ordinal() >= PlacementStatus.DISBURSED.ordinal()
                          || p.getStatus() == PlacementStatus.DISBURSED)
                .map(Placement::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal accrued = BigDecimal.ZERO;
        int paidCount = 0;
        int worstDelay = 0;
        boolean live = false;
        for (Placement p : history) {
            var m = orchestrator.metricsOf(p);
            accrued = accrued.add(m.commissionAccrued());
            paidCount += m.paymentsMade();
            worstDelay = Math.max(worstDelay, m.daysPastDue());
            if (p.getStatus() == PlacementStatus.DISBURSED) live = true;
        }

        String status = worstDelay > 0 ? "overdue"
                : live ? "active"
                : history.stream().anyMatch(p -> p.getStatus() == PlacementStatus.PAID_OFF) ? "paidOff"
                : "onboarding";

        BigDecimal outstanding = history.stream()
                .filter(p -> p.getStatus() == PlacementStatus.DISBURSED)
                .map(Placement::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<FactResponse> facts = List.of(
                new FactResponse("Le has prestado", money(lent), false, false),
                new FactResponse("Saldo hoy", live ? money(outstanding) : "—", false, false),
                new FactResponse(live || paidCount > 0 ? "Te ha generado" : "Te generaría",
                        money(accrued.signum() > 0 ? accrued : projected(history)), true, false),
                new FactResponse("Puntualidad",
                        paidCount == 0 ? "sin historial" : (worstDelay > 0 ? "con atrasos" : "100%"),
                        paidCount > 0 && worstDelay == 0, worstDelay > 0));

        return new BeneficiaryResponse(
                latest.getBeneficiaryPartyId(),
                latest.getBeneficiaryFullName(),
                mask(latest.getBeneficiaryPhone()),
                summaryOf(latest, history.size()),
                status,
                history.size(),
                facts);
    }

    /** Lo que ganaría si cobra todo a tiempo — el dato útil cuando todavía no ha cobrado nada. */
    private BigDecimal projected(List<Placement> history) {
        return history.stream()
                .map(p -> p.getFortnightlyPayment()
                        .multiply(BigDecimal.valueOf(p.getTermFortnights()))
                        .multiply(new BigDecimal("0.20")))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
    }

    private static String summaryOf(Placement latest, int count) {
        String plural = count == 1 ? "1 préstamo" : count + " préstamos";
        return switch (latest.getStatus()) {
            case INVITED, KYC_IN_PROGRESS, KYC_COMPLETED -> "Nueva · terminando su verificación";
            case BUREAU_READY -> "Nueva · espera tu decisión";
            case REJECTED -> "No colocada · " + plural;
            case PAID_OFF -> "Liquidó · " + plural;
            default -> plural + " contigo";
        };
    }

    private static String money(BigDecimal v) {
        return "$" + String.format("%,d", v.setScale(0, RoundingMode.HALF_UP).longValue());
    }

    private static String mask(String phone) {
        if (phone == null || phone.length() != 10) return "•• •• •• •• ••";
        return phone.substring(0, 2) + " •• •• " + phone.substring(6, 8) + " " + phone.substring(8);
    }

    private static UUID currentPartyId() {
        return UUID.fromString(SecurityContextHolder.getContext().getAuthentication().getName());
    }

    record LineSummaryResponse(
            BigDecimal authorized, BigDecimal placed, BigDecimal available,
            BigDecimal toCollect, BigDecimal dueToKredius, BigDecimal commissionAccrued,
            LocalDate nextDueDate, int daysPastDue) {}

    record BeneficiaryListResponse(List<BeneficiaryResponse> beneficiaries) {}

    record BeneficiaryResponse(
            UUID partyId, String fullName, String phoneMask, String summary,
            String status, int placementsCount, List<FactResponse> facts) {}

    /** Valores ya formateados: son de lectura, no de cálculo. */
    record FactResponse(String label, String value, boolean good, boolean bad) {}
}

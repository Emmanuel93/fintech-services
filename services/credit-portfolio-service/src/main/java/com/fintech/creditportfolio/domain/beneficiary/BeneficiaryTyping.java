package com.fintech.creditportfolio.domain.beneficiary;

import com.fintech.creditportfolio.domain.Disposition;
import com.fintech.creditportfolio.domain.DispositionStatus;
import com.fintech.creditportfolio.domain.Installment;
import com.fintech.creditportfolio.domain.InstallmentStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reads a client's type and standing from his placements: pure, so the rule is testable without a database.
 *
 * <p>A placement counts as <b>paid</b> when every installment of its plan is PAID and its line was neither written off
 * nor forgiven part of its debt (quebranto and quita are not payment). It counts as <b>active</b> while the money went
 * out (or is going out) and the plan is not paid. A billed installment past its due date and unpaid puts him on the
 * red list: {@code DISTRIBUTOR_IN_ARREARS} when the line's holder is behind (blocked, days late, written off,
 * restructured), since the statement it was billed in is hers to pay and he may well have paid her;
 * {@code VOUCHER_IN_ARREARS} when her line is current and his voucher is still late. Paying with his own reference
 * brings his installments current and lifts the mark the same day.
 */
public final class BeneficiaryTyping {

    private BeneficiaryTyping() {}

    /**
     * What is known of the line a placement sits on.
     *
     * @param excluded             written off or forgiven part of its debt: nothing paid on it types him REVOLVENTE
     * @param distributorInArrears its holder is behind: the line is blocked by arrears, days late, written off or
     *                             restructured
     * @param blocked              the line refuses placements today (arrears or a quita)
     */
    public record LineFacts(UUID creditAccountId, UUID distributorPartyId, boolean excluded, boolean distributorInArrears,
                            boolean blocked, String blockReason) {}

    /** One placement with its plan. */
    public record PlacementFacts(Disposition disposition, List<Installment> plan, LineFacts line) {}

    public record Standing(ClientType type, int loansGranted, int loansPaid, int activeLoans, BigDecimal outstanding,
                           List<BeneficiaryProfile.LineStanding> lines, List<BeneficiaryProfile.FlagView> derivedFlags) {}

    public static Standing evaluate(UUID beneficiaryPartyId, List<PlacementFacts> placements, LocalDate today, Instant now) {
        int granted = 0, paid = 0, active = 0;
        BigDecimal outstanding = BigDecimal.ZERO;
        Map<UUID, int[]> activeByLine = new LinkedHashMap<>();
        Map<UUID, BigDecimal> owedByLine = new LinkedHashMap<>();
        Map<UUID, Boolean> overdueByLine = new LinkedHashMap<>();
        Map<UUID, LineFacts> lineFacts = new LinkedHashMap<>();

        for (PlacementFacts p : placements) {
            Disposition d = p.disposition();
            if (d.getStatus() == DispositionStatus.FAILED || d.getStatus() == DispositionStatus.REVERSED) continue;
            granted++;
            LineFacts line = p.line();
            lineFacts.putIfAbsent(line.creditAccountId(), line);
            List<Installment> plan = p.plan().stream().filter(i -> !i.getStatus().isVoid()).toList();
            boolean allPaid = !plan.isEmpty() && plan.stream().allMatch(i -> i.getStatus() == InstallmentStatus.PAID);
            if (allPaid && !line.excluded() && d.getStatus() == DispositionStatus.COMPLETED) {
                paid++;
                continue;
            }
            if (allPaid) continue;   // settled by a quita or lost to a write-off: neither paid nor alive
            active++;
            BigDecimal owed = plan.stream().filter(i -> i.getStatus() != InstallmentStatus.PAID)
                    .map(Installment::getPrincipalAmount).filter(a -> a != null).reduce(BigDecimal.ZERO, BigDecimal::add);
            if (plan.isEmpty()) owed = d.getAmount();   // in flight: the plan is not there yet, the money is committed
            outstanding = outstanding.add(owed);
            activeByLine.computeIfAbsent(line.creditAccountId(), k -> new int[1])[0]++;
            owedByLine.merge(line.creditAccountId(), owed, BigDecimal::add);
            boolean overdue = plan.stream().anyMatch(i -> i.getBilledCycle() != null && i.getStatus() != InstallmentStatus.PAID
                    && i.getDueDate() != null && i.getDueDate().isBefore(today));
            overdueByLine.merge(line.creditAccountId(), overdue, Boolean::logicalOr);
        }

        List<BeneficiaryProfile.LineStanding> lines = new ArrayList<>();
        List<BeneficiaryProfile.FlagView> flags = new ArrayList<>();
        for (LineFacts line : lineFacts.values()) {
            int n = activeByLine.getOrDefault(line.creditAccountId(), new int[1])[0];
            BigDecimal owed = owedByLine.getOrDefault(line.creditAccountId(), BigDecimal.ZERO);
            boolean overdue = overdueByLine.getOrDefault(line.creditAccountId(), false);
            lines.add(new BeneficiaryProfile.LineStanding(line.creditAccountId(), line.distributorPartyId(), n, owed, overdue,
                    line.distributorInArrears(), line.blocked(), line.blockReason()));
            if (!overdue) continue;
            // His installment is unpaid past due. With her line behind, the statement it was billed in is hers to pay:
            // the mark names her arrears. With her line current, his voucher itself is late.
            FlagReason reason = line.distributorInArrears() ? FlagReason.DISTRIBUTOR_IN_ARREARS : FlagReason.VOUCHER_IN_ARREARS;
            flags.add(new BeneficiaryProfile.FlagView(null, reason, FlagSource.SYSTEM, now, line.distributorPartyId(),
                    line.creditAccountId(), null, line.distributorInArrears() ? line.blockReason() : null));
        }
        ClientType type = paid > 0 ? ClientType.REVOLVENTE : ClientType.NUEVO;
        return new Standing(type, granted, paid, active, outstanding, lines, flags);
    }
}

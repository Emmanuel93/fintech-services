package com.fintech.receipt.application.service.assembly;

import com.fintech.receipt.domain.CutoffFacts;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Everything one receipt is assembled from, frozen when it is generated and stored as its snapshot: a regeneration
 * prints the same receipt in a new design without asking any service again.
 *
 * @param clientType  NUEVO or REVOLVENTE when it was printed; null if the portfolio could not tell
 * @param loansPaid   the loans he has fully paid (any distributor): what makes him REVOLVENTE
 * @param flags       his red-list reasons when it was printed (no comments: those are not his to read)
 * @param live        his loans with her that bill something or still owe principal, oldest first
 * @param settled     his loans with her paid off in this period
 */
public record ReceiptInputs(CutoffFacts cutoff, UUID beneficiaryPartyId, Party client, Party distributor, String clabe,
                            Long paymentReference, String clientType, int loansPaid, List<String> flags, List<Loan> live,
                            List<Loan> settled, Instant issuedAt) {

    public record Party(UUID partyId, String name, String rfc) {}

    public record Loan(UUID dispositionId, Instant placedAt, BigDecimal amount, int totalInstallments,
                       BigDecimal outstandingPrincipal, List<Installment> due, int overdueInstallments,
                       LocalDate oldestOverdueDue, LocalDate settledOn) {

        public List<Installment> dueOrEmpty() { return due == null ? List.of() : due; }

        /** What he pays of this loan in this receipt: principal, interest and IVA of what the statement bills. */
        public BigDecimal payment() {
            return dueOrEmpty().stream().map(Installment::totalOrSum).reduce(BigDecimal.ZERO, BigDecimal::add);
        }
    }

    public record Installment(int number, LocalDate dueDate, BigDecimal principal, BigDecimal interest, BigDecimal tax,
                              BigDecimal total, LocalDate originalDueDate) {

        public BigDecimal totalOrSum() {
            return total != null ? total : nz(principal).add(nz(interest)).add(nz(tax));
        }

        static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
    }

    /** What he pays this fortnight: the sum of his loans' payments, without her discount. */
    public BigDecimal totalDue() {
        return live.stream().map(Loan::payment).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** The folio: the cutoff and his reference identify the receipt for him and for her. */
    public String folio() {
        return "RC-" + cutoff.cutoffDate().toString().replace("-", "") + "-"
                + (paymentReference != null ? paymentReference : beneficiaryPartyId.toString().substring(0, 8).toUpperCase());
    }
}

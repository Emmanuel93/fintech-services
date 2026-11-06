package com.fintech.receipt.support;

import com.fintech.receipt.application.port.out.ReceiptSource;
import com.fintech.receipt.application.service.assembly.ReceiptInputs;
import com.fintech.receipt.domain.CutoffFacts;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** A distributor's line on the cutoff of 21 Oct 2025 (due 3 Nov), with two clients billed and one not. */
public final class ReceiptSamples {

    private ReceiptSamples() {}

    public static final LocalDate FROM = LocalDate.of(2025, 10, 7);
    public static final LocalDate CUTOFF = LocalDate.of(2025, 10, 21);
    public static final LocalDate DUE = LocalDate.of(2025, 11, 3);

    public static CutoffFacts facts(UUID line, UUID her, String productType) {
        return new CutoffFacts("evt-" + UUID.randomUUID(), UUID.randomUUID(), line, her, productType, "REVOLVING",
                "DISTRIBUTOR", 21, FROM, CUTOFF, DUE, new BigDecimal("15200.00"), new BigDecimal("14000.00"),
                new BigDecimal("1200.00"), new BigDecimal("2600.00"), null, 0);
    }

    public static ReceiptSource.InstallmentLine installment(int n, String principal, String interest, String tax) {
        BigDecimal p = new BigDecimal(principal), i = new BigDecimal(interest), t = new BigDecimal(tax);
        return new ReceiptSource.InstallmentLine(n, DUE, p, i, t, p.add(i).add(t), "PENDING", CUTOFF);
    }

    /** Ana owes two loans (one with an overdue payment), Beto one, Carla one placed after the cutoff (nothing billed). */
    public static ReceiptSource.LineView view(UUID line, UUID her, UUID ana, UUID beto, UUID carla) {
        List<ReceiptSource.PlacementLine> placements = new ArrayList<>();
        placements.add(new ReceiptSource.PlacementLine(UUID.randomUUID(), ana, new BigDecimal("5000.00"),
                Instant.parse("2025-06-10T16:00:00Z"), 12, 8, new BigDecimal("1690.00"),
                List.of(installment(8, "410.00", "66.90", "10.70"), installment(9, "426.20", "50.70", "8.11")),
                1, LocalDate.of(2025, 10, 18), false, null));
        placements.add(new ReceiptSource.PlacementLine(UUID.randomUUID(), ana, new BigDecimal("3000.00"),
                Instant.parse("2025-09-02T16:00:00Z"), 12, 2, new BigDecimal("2500.00"),
                List.of(installment(3, "250.00", "99.00", "15.84")), 0, null, false, null));
        placements.add(new ReceiptSource.PlacementLine(UUID.randomUUID(), beto, new BigDecimal("10000.00"),
                Instant.parse("2025-04-15T16:00:00Z"), 24, 12, new BigDecimal("6000.00"),
                List.of(installment(13, "480.00", "237.50", "38.00")), 0, null, false, null));
        placements.add(new ReceiptSource.PlacementLine(UUID.randomUUID(), beto, new BigDecimal("2000.00"),
                Instant.parse("2025-01-20T16:00:00Z"), 12, 12, BigDecimal.ZERO, List.of(), 0, null, true,
                LocalDate.of(2025, 10, 15)));
        placements.add(new ReceiptSource.PlacementLine(UUID.randomUUID(), carla, new BigDecimal("4000.00"),
                Instant.parse("2025-10-20T16:00:00Z"), 12, 0, new BigDecimal("4000.00"), List.of(), 0, null, false, null));
        return new ReceiptSource.LineView(new ReceiptSource.Account(line, "CTR-DL-1", "DISTRIBUTOR_LINE", her,
                "646180157000000004"), FROM, CUTOFF, DUE, placements);
    }

    public static ReceiptInputs inputs(boolean redListed) {
        UUID line = UUID.randomUUID(), her = UUID.randomUUID(), client = UUID.randomUUID();
        CutoffFacts f = facts(line, her, "DISTRIBUTOR_LINE");
        List<ReceiptInputs.Loan> live = List.of(
                new ReceiptInputs.Loan(UUID.randomUUID(), Instant.parse("2025-06-10T16:00:00Z"), new BigDecimal("5000.00"), 12,
                        new BigDecimal("1690.00"), List.of(
                        new ReceiptInputs.Installment(8, DUE, new BigDecimal("410.00"), new BigDecimal("66.90"), new BigDecimal("10.70"), new BigDecimal("487.60"), CUTOFF),
                        new ReceiptInputs.Installment(9, DUE, new BigDecimal("426.20"), new BigDecimal("50.70"), new BigDecimal("8.11"), new BigDecimal("485.01"), CUTOFF)),
                        1, LocalDate.of(2025, 10, 18), null),
                new ReceiptInputs.Loan(UUID.randomUUID(), Instant.parse("2025-09-02T16:00:00Z"), new BigDecimal("3000.00"), 12,
                        new BigDecimal("2500.00"), List.of(
                        new ReceiptInputs.Installment(3, DUE, new BigDecimal("250.00"), new BigDecimal("99.00"), new BigDecimal("15.84"), new BigDecimal("364.84"), CUTOFF)),
                        0, null, null));
        List<ReceiptInputs.Loan> settled = List.of(new ReceiptInputs.Loan(UUID.randomUUID(), Instant.parse("2025-01-20T16:00:00Z"),
                new BigDecimal("2000.00"), 12, BigDecimal.ZERO, List.of(), 0, null, LocalDate.of(2025, 10, 15)));
        return new ReceiptInputs(f, client, new ReceiptInputs.Party(client, "Rosa Elena Ibarra Salgado", "IASR900612B0B"),
                new ReceiptInputs.Party(her, "Laura Méndez Ortiz", null), "646180157000000004", 1234566L, "REVOLVENTE", 2,
                redListed ? List.of("VOUCHER_IN_ARREARS") : List.of(), live, settled, Instant.parse("2025-10-22T12:00:00Z"));
    }
}

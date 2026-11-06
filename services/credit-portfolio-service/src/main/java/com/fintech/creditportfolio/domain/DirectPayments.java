package com.fintech.creditportfolio.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A distributor's clients paying their own loans: what each client's deposits cover of his installments.
 *
 * <p>Each client's money, net of what came back, covers his installments already billed when it arrived (their
 * statement's cutoff on or before the day the money landed), oldest first. It never pays ahead an installment no
 * statement has billed yet: what is left over of his deposit is her line's (it lowered her balance like any payment).
 * Pure and recomputed from the whole ledger, so a returned deposit uncovers what it had covered.
 */
public final class DirectPayments {

    private static final BigDecimal CENT = new BigDecimal("0.01");

    private DirectPayments() {}

    /** A client's deposit, net of returns. */
    public record Deposit(String key, UUID client, LocalDate valueDate, BigDecimal amount) {}

    /**
     * The plan installments the clients' own deposits pay in full.
     *
     * @param clientOfPlan  the client of each placement (its plan's schedule id)
     * @param cutoffOfCycle the cutoff date of each statement cycle of the line
     */
    public static Set<UUID> covered(List<Deposit> deposits, List<Installment> plans, Map<UUID, UUID> clientOfPlan,
                                    Map<Integer, LocalDate> cutoffOfCycle) {
        if (deposits.isEmpty()) return Set.of();
        Map<UUID, List<Installment>> byClient = new HashMap<>();
        for (Installment i : plans) {
            UUID client = clientOfPlan.get(i.getScheduleId());
            if (client == null || i.getBilledCycle() == null || i.getStatus().isVoid()) continue;
            byClient.computeIfAbsent(client, k -> new ArrayList<>()).add(i);
        }
        Map<UUID, List<Deposit>> depositsByClient = new LinkedHashMap<>();
        deposits.stream()
                .sorted(Comparator.comparing(Deposit::valueDate, Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(Deposit::key))
                .forEach(d -> depositsByClient.computeIfAbsent(d.client(), k -> new ArrayList<>()).add(d));

        Set<UUID> paid = new HashSet<>();
        for (Map.Entry<UUID, List<Deposit>> e : depositsByClient.entrySet()) {
            List<Installment> his = byClient.getOrDefault(e.getKey(), List.of()).stream()
                    .sorted(Comparator.comparing(Installment::getDueDate).thenComparing(Installment::getInstallmentNumber))
                    .toList();
            Map<UUID, BigDecimal> owed = new HashMap<>();
            his.forEach(i -> owed.put(i.getInstallmentId(), i.getTotalAmount()));
            for (Deposit d : e.getValue()) {
                BigDecimal left = d.amount();
                for (Installment i : his) {
                    if (left.signum() <= 0) break;
                    LocalDate billedOn = cutoffOfCycle.get(i.getBilledCycle());
                    if (billedOn == null || (d.valueDate() != null && billedOn.isAfter(d.valueDate()))) continue;
                    BigDecimal stillOwed = owed.get(i.getInstallmentId());
                    if (stillOwed.compareTo(CENT) < 0) continue;
                    BigDecimal take = left.min(stillOwed);
                    owed.put(i.getInstallmentId(), stillOwed.subtract(take));
                    left = left.subtract(take);
                }
            }
            his.stream().filter(i -> owed.get(i.getInstallmentId()).compareTo(CENT) < 0)
                    .forEach(i -> paid.add(i.getInstallmentId()));
        }
        return paid;
    }
}

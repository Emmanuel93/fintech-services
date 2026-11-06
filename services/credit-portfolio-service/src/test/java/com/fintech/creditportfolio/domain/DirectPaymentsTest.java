package com.fintech.creditportfolio.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DirectPaymentsTest {

    static final LocalDate CUT1 = LocalDate.of(2025, 10, 6), CUT2 = LocalDate.of(2025, 10, 21), CUT3 = LocalDate.of(2025, 11, 6);
    final UUID ana = UUID.randomUUID(), beto = UUID.randomUUID();
    final UUID planAna = UUID.randomUUID(), planBeto = UUID.randomUUID();
    final Map<Integer, LocalDate> cutoffs = Map.of(1, CUT1, 2, CUT2, 3, CUT3);
    final Map<UUID, UUID> clientOf = Map.of(planAna, ana, planBeto, beto);

    Installment billed(UUID plan, int n, LocalDate due, int cycle, String total) {
        Installment i = Installment.of(plan, n, due, new BigDecimal(total).subtract(new BigDecimal("100")), new BigDecimal("100"));
        if (cycle > 0) i.billIn(cycle, due);
        return i;
    }

    @Test
    @DisplayName("su depósito cubre sus cuotas ya facturadas, la más vieja primero; nunca una que ningún estado ha facturado")
    void coversHisBilledInstallmentsOldestFirst() {
        Installment a1 = billed(planAna, 1, LocalDate.of(2025, 10, 18), 1, "500.00");
        Installment a2 = billed(planAna, 2, LocalDate.of(2025, 11, 3), 2, "500.00");
        Installment a3 = billed(planAna, 3, LocalDate.of(2025, 11, 18), 0, "500.00");   // not billed yet
        List<Installment> plans = List.of(a1, a2, a3);

        // 1,200 on 25 Oct: it covers his installments of the cutoffs of 6 and 21 Oct; 200 are left for her line.
        Set<UUID> paid = DirectPayments.covered(List.of(new DirectPayments.Deposit("p1", ana, LocalDate.of(2025, 10, 25),
                new BigDecimal("1200.00"))), plans, clientOf, cutoffs);
        assertThat(paid).containsExactlyInAnyOrder(a1.getInstallmentId(), a2.getInstallmentId());
    }

    @Test
    @DisplayName("un depósito anterior al corte que factura la cuota no la paga: no adelanta lo que ningún estado pidió")
    void aDepositBeforeTheCutoffDoesNotPayAhead() {
        Installment a2 = billed(planAna, 2, LocalDate.of(2025, 11, 3), 2, "500.00");
        Set<UUID> paid = DirectPayments.covered(List.of(new DirectPayments.Deposit("p1", ana, LocalDate.of(2025, 10, 20),
                new BigDecimal("500.00"))), List.of(a2), clientOf, cutoffs);
        assertThat(paid).isEmpty();
    }

    @Test
    @DisplayName("cada cliente cubre sólo lo suyo; un pago corto no da por pagada la cuota; varios depósitos suman")
    void eachClientHisOwn_shortDepositsAccumulate() {
        Installment a1 = billed(planAna, 1, LocalDate.of(2025, 10, 18), 1, "500.00");
        Installment b1 = billed(planBeto, 1, LocalDate.of(2025, 10, 18), 1, "800.00");
        List<Installment> plans = List.of(a1, b1);
        assertThat(DirectPayments.covered(List.of(new DirectPayments.Deposit("p1", beto, LocalDate.of(2025, 10, 10),
                new BigDecimal("799.99"))), plans, clientOf, cutoffs)).isEmpty();
        assertThat(DirectPayments.covered(List.of(
                new DirectPayments.Deposit("p1", beto, LocalDate.of(2025, 10, 10), new BigDecimal("400.00")),
                new DirectPayments.Deposit("p2", beto, LocalDate.of(2025, 10, 12), new BigDecimal("400.00"))),
                plans, clientOf, cutoffs)).containsExactly(b1.getInstallmentId());
    }

    @Test
    @DisplayName("rendimiento: 200 clientes con 24 cuotas y 12 depósitos cada uno, en milisegundos")
    void manyClientsAreCheap() {
        List<Installment> plans = new ArrayList<>();
        List<DirectPayments.Deposit> deposits = new ArrayList<>();
        Map<UUID, UUID> clients = new HashMap<>();
        Map<Integer, LocalDate> cuts = new HashMap<>();
        for (int c = 1; c <= 48; c++) cuts.put(c, LocalDate.of(2025, 1, 6).plusDays(15L * (c - 1)));
        for (int k = 0; k < 200; k++) {
            UUID client = UUID.randomUUID(), plan = UUID.randomUUID();
            clients.put(plan, client);
            for (int n = 1; n <= 24; n++) plans.add(billed(plan, n, cuts.get(n).plusDays(12), n, "450.00"));
            for (int d = 1; d <= 12; d++) deposits.add(new DirectPayments.Deposit(client + ":" + d, client, cuts.get(d).plusDays(5), new BigDecimal("450.00")));
        }
        long start = System.nanoTime();
        Set<UUID> paid = DirectPayments.covered(deposits, plans, clients, cuts);
        long ms = (System.nanoTime() - start) / 1_000_000;
        assertThat(paid).hasSize(200 * 12);
        assertThat(ms).isLessThan(1000);
    }
}

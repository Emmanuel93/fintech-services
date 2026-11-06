package com.fintech.banking.treasury;

import com.fintech.banking.application.port.out.CashMovementPublisher;
import com.fintech.banking.application.port.out.InternalMovementStore;
import com.fintech.banking.application.service.CashBookProjector;
import com.fintech.banking.domain.InternalMovement;
import com.fintech.banking.infrastructure.adapter.in.messaging.TreasuryCashListener;
import com.fintech.banking.infrastructure.adapter.in.messaging.TreasuryCashListener.CreditReceivedPayload;
import com.fintech.banking.infrastructure.adapter.in.messaging.TreasuryCashListener.PaymentAppliedPayload;
import com.fintech.banking.infrastructure.adapter.in.messaging.TreasuryCashListener.PaymentRejectedPayload;
import com.fintech.banking.infrastructure.adapter.in.messaging.TreasuryCashListener.PaymentReturnedPayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Treasury's cash book: every movement of the account once, on the bank's date, announced so accounting can reconcile
 * 1101 against it. Collections enter with the credit the bank reported on a collection CLABE; a direct payment brings
 * its own money; a share of a received credit or a balance in favor brings none.
 */
class CashBookTest {

    /** The cash book in memory: one movement per fact, as the database keeps it. */
    private static final class InMemoryCashBook implements InternalMovementStore {
        final Map<String, InternalMovement> byFact = new HashMap<>();
        final List<InternalMovement> stored = new ArrayList<>();

        @Override
        public InternalMovement save(InternalMovement m) {
            return byFact.computeIfAbsent(m.getSourceEventId(), k -> {
                stored.add(m);
                return m;
            });
        }

        @Override
        public boolean contains(String sourceEventId) {
            return byFact.containsKey(sourceEventId);
        }
    }

    private final InMemoryCashBook book = new InMemoryCashBook();
    private final List<InternalMovement> announced = new ArrayList<>();
    private TreasuryCashListener listener;

    @BeforeEach
    void setUp() {
        CashMovementPublisher publisher = announced::add;
        listener = new TreasuryCashListener(new CashBookProjector(book, publisher));
    }

    @Test
    @DisplayName("un pago directo entra en su fecha valor; la reentrega se guarda una vez y se anuncia otra")
    void aDirectPaymentComesInOnce() {
        UUID account = UUID.randomUUID();
        var payment = new PaymentAppliedPayload("order-1", account, new BigDecimal("1530.25"), LocalDate.of(2026, 9, 15), null);

        listener.onPaymentApplied(payment);
        listener.onPaymentApplied(payment);

        assertThat(book.stored).hasSize(1);
        // Announced again, the same movement: if the first announcement was lost, accounting still gets it, once.
        assertThat(announced).hasSize(2).allSatisfy(a -> assertThat(a).isSameAs(book.stored.get(0)));
        InternalMovement m = book.stored.get(0);
        assertThat(m.getMovementType()).isEqualTo("CLIENT_PAYMENT");
        assertThat(m.getDirection()).isEqualTo("CREDIT");
        assertThat(m.getAmount()).isEqualByComparingTo("1530.25");
        assertThat(m.getBusinessDate()).isEqualTo(LocalDate.of(2026, 9, 15));
    }

    @Test
    @DisplayName("el abono del 30-nov a las 19:00 entra a caja con fecha de operación 1-dic y fecha de cartera 30-nov")
    void aReceivedCreditKeepsBothDates() {
        UUID credit = UUID.randomUUID();
        listener.onCreditReceived(new CreditReceivedPayload(credit, "SIMSPEI20261201000001", LocalDate.of(2026, 12, 1),
                Instant.parse("2026-12-01T01:00:00Z"), new BigDecimal("5000.00"), "CONFIRMED"));

        assertThat(book.stored).hasSize(1);
        InternalMovement m = book.stored.get(0);
        assertThat(m.getMovementType()).isEqualTo("CLIENT_PAYMENT");
        assertThat(m.getBusinessDate()).as("la del banco").isEqualTo(LocalDate.of(2026, 12, 1));
        assertThat(m.getPortfolioDate()).as("la de cartera").isEqualTo(LocalDate.of(2026, 11, 30));
        assertThat(m.getTrackingKey()).isEqualTo("SIMSPEI20261201000001");
        assertThat(m.getSourceEventId()).isEqualTo("CREDIT:" + credit);
    }

    @Test
    @DisplayName("la parte de un abono que paga un crédito y el saldo a favor aplicado no vuelven a entrar a caja")
    void sharesOfACreditBringNoMoney() {
        UUID account = UUID.randomUUID();
        listener.onPaymentApplied(new PaymentAppliedPayload("order-5", account, new BigDecimal("2500"), LocalDate.of(2026, 11, 30), "SPEI_CREDIT"));
        listener.onPaymentApplied(new PaymentAppliedPayload("order-6", account, new BigDecimal("800"), LocalDate.of(2026, 12, 2), "CREDIT_BALANCE"));
        // And if the portfolio rejects such a share, nothing goes back: it stays in favor of the payer.
        listener.onPaymentRejected(new PaymentRejectedPayload("order-5", account, new BigDecimal("2500"),
                Instant.parse("2026-12-01T02:00:00Z")));

        assertThat(book.stored).isEmpty();
    }

    @Test
    @DisplayName("un abono devuelto entra y sale el mismo día de operación, como lo muestra el estado de cuenta del banco")
    void aReturnedCreditComesInAndGoesOut() {
        UUID credit = UUID.randomUUID();
        listener.onCreditReceived(new CreditReceivedPayload(credit, "BANCO777", LocalDate.of(2026, 11, 3),
                Instant.parse("2026-10-31T17:00:00Z"), new BigDecimal("300.00"), "RETURNED"));

        assertThat(book.stored).extracting(InternalMovement::getMovementType, InternalMovement::getDirection)
                .containsExactly(tuple("CLIENT_PAYMENT", "CREDIT"),
                        tuple("PAYMENT_RETURN", "DEBIT"));
        assertThat(book.stored).allSatisfy(m -> assertThat(m.getBusinessDate()).isEqualTo(LocalDate.of(2026, 11, 3)));
    }

    @Test
    @DisplayName("un pago devuelto y un rechazo de un pago directo salen; las comisiones salen como orden STP, no de aquí")
    void whatGoesOut() {
        UUID account = UUID.randomUUID();
        listener.onPaymentApplied(new PaymentAppliedPayload("order-3", account, new BigDecimal("90"), LocalDate.of(2026, 9, 16), "DIRECT"));
        listener.onPaymentReturned(new PaymentReturnedPayload("order-2", account, new BigDecimal("-400"),
                LocalDate.of(2026, 9, 16)));
        listener.onPaymentRejected(new PaymentRejectedPayload("order-3", account, new BigDecimal("90"),
                Instant.parse("2026-09-17T05:30:00Z")));   // 23:30 of the 16th in Mexico City
        List<InternalMovement> out = book.stored.stream().filter(m -> "DEBIT".equals(m.getDirection())).toList();
        assertThat(out).extracting(InternalMovement::getMovementType)
                .containsExactly("PAYMENT_RETURN", "PAYMENT_RETURN");
        assertThat(out.get(0).getAmount()).isEqualByComparingTo("400");
        assertThat(out.get(1).getBusinessDate()).as("the business day in Mexico City").isEqualTo(LocalDate.of(2026, 9, 16));
        assertThat(out.get(1).getSourceEventId()).isEqualTo("PAYMENT-REJECTED:order-3");
    }

    @Test
    @DisplayName("un hecho sin dinero no llega al libro")
    void nothingWithoutMoney() {
        listener.onPaymentApplied(new PaymentAppliedPayload("order-4", UUID.randomUUID(), BigDecimal.ZERO, null, null));
        listener.onCreditReceived(new CreditReceivedPayload(UUID.randomUUID(), "X", null, null, new BigDecimal("1"), "CONFIRMED"));

        assertThat(book.stored).isEmpty();
        assertThat(announced).isEmpty();
    }
}

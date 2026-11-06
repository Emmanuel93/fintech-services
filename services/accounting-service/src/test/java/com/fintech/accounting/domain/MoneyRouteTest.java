package com.fintech.accounting.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where each peso is booked: the account a payment's money comes from, the bank side of each movement of treasury's
 * cash book, the item of the clearing account each one moves, and the kind of voucher that follows from the cash.
 */
class MoneyRouteTest {

    private final UUID credit = UUID.fromString("7d0e1c55-0000-4000-8000-000000000001");

    @Test
    @DisplayName("un pago por abono SPEI espera en 1103 con el abono como partida; el saldo a favor sale de 2102; lo directo, de Bancos")
    void aPaymentIsBookedAgainstWhereItsMoneyIs() {
        PaymentOrigin spei = new PaymentOrigin(PaymentOrigin.SPEI_CREDIT, credit);
        assertThat(spei.debitAccount()).isEqualTo(AccountCodes.REMESAS_EN_CAMINO);
        assertThat(spei.clearingRef()).isEqualTo(credit.toString());

        PaymentOrigin balance = new PaymentOrigin(PaymentOrigin.CREDIT_BALANCE, null);
        assertThat(balance.debitAccount()).isEqualTo(AccountCodes.SALDOS_A_FAVOR);
        assertThat(balance.clearingRef()).isNull();

        for (PaymentOrigin direct : List.of(PaymentOrigin.direct(), new PaymentOrigin("DIRECT", null),
                new PaymentOrigin("UNKNOWN", credit))) {
            assertThat(direct.debitAccount()).isEqualTo(AccountCodes.BANCOS);
            assertThat(direct.clearingRef()).isNull();
        }
        // A share of a credit that does not say which still waits in transit: the close finds it without an item.
        assertThat(new PaymentOrigin(PaymentOrigin.SPEI_CREDIT, null).clearingRef()).isNull();
    }

    @Test
    @DisplayName("el lado banco: el abono acreditado limpia 1103, su devolución lo deshace, la orden liquidada limpia 2103")
    void theBankSideClearsTheTransitItsBusinessSideUsed() {
        BankSideRule credited = BankSideRule.of("CLIENT_PAYMENT", "CREDIT:" + credit).orElseThrow();
        assertThat(credited.debit()).isEqualTo(AccountCodes.BANCOS);
        assertThat(credited.credit()).isEqualTo(AccountCodes.REMESAS_EN_CAMINO);
        assertThat(credited.clearingRef("CREDIT:" + credit, "SIMSPEI1")).isEqualTo(credit.toString());

        BankSideRule returned = BankSideRule.of("PAYMENT_RETURN", "CREDIT-RETURNED:" + credit).orElseThrow();
        assertThat(returned.debit()).isEqualTo(AccountCodes.REMESAS_EN_CAMINO);
        assertThat(returned.credit()).isEqualTo(AccountCodes.BANCOS);
        assertThat(returned.clearingRef("CREDIT-RETURNED:" + credit, null)).isEqualTo(credit.toString());

        UUID order = UUID.randomUUID();
        BankSideRule settled = BankSideRule.of("STP_ORDER", "settled-1").orElseThrow();
        assertThat(settled.debit()).isEqualTo(AccountCodes.SPEI_POR_LIQUIDAR);
        assertThat(settled.credit()).isEqualTo(AccountCodes.BANCOS);
        assertThat(settled.clearingRef("settled-1", order.toString())).isEqualTo(order.toString());
    }

    @Test
    @DisplayName("lo que mueve 1101 por su propio hecho no tiene lado banco: fondeo, comisiones, pagos directos y sus rechazos")
    void movementsBookedByTheirOwnFactHaveNoBankSide() {
        assertThat(BankSideRule.of("FUNDING", "FUNDING:1")).isEmpty();
        assertThat(BankSideRule.of("COMMISSION_PAYOUT", "COMMISSION:1")).isEmpty();
        assertThat(BankSideRule.of("CLIENT_PAYMENT", "PAYMENT:order-1")).isEmpty();
        assertThat(BankSideRule.of("PAYMENT_RETURN", "PAYMENT-REJECTED:order-1")).isEmpty();
        assertThat(BankSideRule.of("PAYMENT_RETURN", "order-2")).isEmpty();
    }

    @Test
    @DisplayName("el tipo de póliza sale del efectivo: entra a Bancos es ingreso, sale es egreso, lo demás es diario")
    void theVoucherTypeFollowsTheCash() {
        assertThat(VoucherType.ofCash(List.of(AccountCodes.BANCOS), List.of(AccountCodes.REMESAS_EN_CAMINO)))
                .isEqualTo(VoucherType.INGRESO);
        assertThat(VoucherType.ofCash(List.of(AccountCodes.SPEI_POR_LIQUIDAR), List.of(AccountCodes.BANCOS)))
                .isEqualTo(VoucherType.EGRESO);
        // A payment waiting in transit moves no cash yet.
        assertThat(VoucherType.ofCash(List.of(AccountCodes.REMESAS_EN_CAMINO), List.of(AccountCodes.CARTERA_VIGENTE)))
                .isEqualTo(VoucherType.DIARIO);
    }
}

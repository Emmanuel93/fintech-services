package com.fintech.accounting;

import com.fintech.accounting.application.port.out.AccountBalanceShadowRepository;
import com.fintech.accounting.application.port.out.ItemProducts;
import com.fintech.accounting.application.service.ProductDimension;
import com.fintech.accounting.application.service.VoucherPostingService.VoucherRequest;
import com.fintech.accounting.domain.AccountBalanceShadow;
import com.fintech.accounting.domain.BankSideRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The product a voucher belongs to: the one its fact names, else its loan's, else its clearing item's; the
 * institution's own facts belong to none.
 */
class ProductDimensionTest {

    private static final Instant AT = Instant.parse("2026-05-10T18:00:00Z");

    private final Map<UUID, AccountBalanceShadow> shadows = new HashMap<>();
    private final Map<String, String> orders = new HashMap<>();
    private final Map<String, String> credits = new HashMap<>();
    private final ProductDimension products = new ProductDimension(new InMemoryShadows(), new InMemoryItems());

    private UUID loanOf(String product) {
        UUID loan = UUID.randomUUID();
        AccountBalanceShadow shadow = AccountBalanceShadow.init(loan, UUID.randomUUID());
        shadow.learnProduct(product);
        shadows.put(loan, shadow);
        return loan;
    }

    private static VoucherRequest ofLoan(UUID loan) {
        return new VoucherRequest("evt-" + loan, "PAYMENT_APPLIED", AT, loan, null, "S_UNO", "Pago");
    }

    private static VoucherRequest bankSide(BankSideRule rule, String item) {
        return new VoucherRequest(rule.name() + ":" + item, rule.trigger(), AT, null, null, null, "Banco", item);
    }

    @Test
    @DisplayName("a loan's voucher carries the product its activation published")
    void theLoansProduct() {
        UUID loan = loanOf("PERSONAL_LOAN");
        assertThat(products.of(ofLoan(loan))).isEqualTo("PERSONAL_LOAN");
    }

    @Test
    @DisplayName("the product a fact names wins: a commission knows its own")
    void theFactsOwn() {
        UUID loan = loanOf("PERSONAL_LOAN");
        assertThat(products.of(ofLoan(loan).withProduct("DISTRIBUTOR_LINE"))).isEqualTo("DISTRIBUTOR_LINE");
        assertThat(products.of(new VoucherRequest("lot", "COMMISSION_LOT_PAYOUT", AT, null, null, null, "Lote",
                "LOTE:1").withProduct("DISTRIBUTOR_LINE"))).isEqualTo("DISTRIBUTOR_LINE");
    }

    @Test
    @DisplayName("a loan whose product is not known yet is not given its item's: the sweep attributes it later")
    void aLoanNotKnownYet() {
        UUID loan = UUID.randomUUID();
        credits.put("credit-1", "PERSONAL_LOAN");
        assertThat(products.of(new VoucherRequest("evt", "PAYMENT_APPLIED", AT, loan, null, null, "Pago", "credit-1")))
                .isNull();
    }

    @Test
    @DisplayName("the bank side of an order is its loan's or lot's product; of a received credit and its return, the "
            + "one product the portfolio applied it to")
    void theBankSide() {
        orders.put("order-1", "REVOLVING_CARD");
        credits.put("credit-1", "PERSONAL_LOAN");
        assertThat(products.of(bankSide(BankSideRule.STP_ORDER, "order-1"))).isEqualTo("REVOLVING_CARD");
        assertThat(products.of(bankSide(BankSideRule.SPEI_CREDIT, "credit-1"))).isEqualTo("PERSONAL_LOAN");
        assertThat(products.of(bankSide(BankSideRule.SPEI_CREDIT_RETURNED, "credit-1"))).isEqualTo("PERSONAL_LOAN");
        assertThat(products.of(bankSide(BankSideRule.STP_ORDER, "order-not-linked-yet"))).isNull();
    }

    @Test
    @DisplayName("the institution's own facts (its funding, its interest) and an item of another kind belong to no product")
    void theInstitutions() {
        orders.put("order-1", "REVOLVING_CARD");
        assertThat(products.of(new VoucherRequest("fund", "FUNDING_INTEREST", AT, null, null, null, "Fondeo"))).isNull();
        assertThat(products.of(new VoucherRequest("x", "FUNDING_DRAWDOWN", AT, null, null, null, "Fondeo", "order-1")))
                .isNull();
    }

    @Test
    @DisplayName("a loan's product is sealed the first time it is known: a later delivery cannot move it")
    void sealedOnce() {
        AccountBalanceShadow shadow = AccountBalanceShadow.init(UUID.randomUUID(), UUID.randomUUID());
        shadow.learnProduct("  ");
        assertThat(shadow.getProductType()).isNull();
        shadow.learnProduct("PERSONAL_LOAN");
        shadow.learnProduct("SME_LOAN");
        assertThat(shadow.getProductType()).isEqualTo("PERSONAL_LOAN");
    }

    @Test
    @DisplayName("performance: 200,000 vouchers of 20,000 loans and their bank side attributed in under a second")
    void performance() {
        List<VoucherRequest> requests = new ArrayList<>();
        String[] kinds = {"PERSONAL_LOAN", "DISTRIBUTOR_LINE", "REVOLVING_CARD", "SME_LOAN"};
        for (int i = 0; i < 20_000; i++) {
            UUID loan = loanOf(kinds[i % kinds.length]);
            orders.put("order-" + i, kinds[i % kinds.length]);
            for (int j = 0; j < 9; j++) requests.add(ofLoan(loan));
            requests.add(bankSide(BankSideRule.STP_ORDER, "order-" + i));
        }
        long start = System.nanoTime();
        Map<String, Integer> byProduct = new HashMap<>();
        for (VoucherRequest r : requests) byProduct.merge(products.of(r), 1, Integer::sum);
        long millis = (System.nanoTime() - start) / 1_000_000;

        assertThat(byProduct).hasSize(4).allSatisfy((product, n) -> assertThat(n).isEqualTo(50_000));
        assertThat(millis).as("ms para atribuir %d pólizas", requests.size()).isLessThan(1_000L);
    }

    private final class InMemoryShadows implements AccountBalanceShadowRepository {
        @Override public Optional<AccountBalanceShadow> findById(UUID id) { return Optional.ofNullable(shadows.get(id)); }
        @Override public List<AccountBalanceShadow> findAll() { return List.copyOf(shadows.values()); }
        @Override public AccountBalanceShadow save(AccountBalanceShadow s) { shadows.put(s.getCreditAccountId(), s); return s; }
    }

    private final class InMemoryItems implements ItemProducts {
        @Override public Optional<String> ofOrder(String id) { return Optional.ofNullable(orders.get(id)); }
        @Override public Optional<String> ofReceivedCredit(String ref) { return Optional.ofNullable(credits.get(ref)); }
        @Override public int sweepOpenPeriods() { return 0; }
    }
}

package com.fintech.accounting;

import com.fintech.accounting.application.AccountingProperties;
import com.fintech.accounting.application.port.out.FundingBalanceRepository;
import com.fintech.accounting.application.service.FeeDeferralService;
import com.fintech.accounting.application.service.FundingPostingService;
import com.fintech.accounting.application.service.FundingPostingService.FundingFact;
import com.fintech.accounting.application.service.VoucherPostingService;
import com.fintech.accounting.application.service.VoucherPostingService.Pair;
import com.fintech.accounting.application.service.VoucherPostingService.VoucherRequest;
import com.fintech.accounting.domain.AccountCodes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

/**
 * Where the lent money comes from, booked: capital is equity, a line is a liability at amortised cost with its
 * interest an expense of each day, and the line's opening fee a transaction cost amortized into that expense.
 */
class FundingPostingServiceTest {

    private final VoucherPostingService ledger = mock(VoucherPostingService.class);
    private final FundingBalanceRepository balances = mock(FundingBalanceRepository.class);
    private final FeeDeferralService deferrals = mock(FeeDeferralService.class);
    private FundingPostingService service;
    private final UUID source = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        AccountingProperties properties = new AccountingProperties();
        properties.setZone("America/Mexico_City");
        service = new FundingPostingService(ledger, balances, deferrals, properties);
    }

    private FundingFact fact(String type, String amount, String principalAfter, String interestAfter, String contributed) {
        return new FundingFact("FUNDING:" + UUID.randomUUID(), UUID.randomUUID(), source,
                type.equals("CONTRIBUTION") ? "CAPITAL" : "CREDIT_LINE", "Banco de Fomento de Ejemplo", "CONTRATO-1", type,
                new BigDecimal(amount), LocalDate.of(2026, 3, 31), LocalDate.of(2026, 3, 2), LocalDate.of(2026, 3, 31),
                new BigDecimal(contributed), new BigDecimal(principalAfter), new BigDecimal(interestAfter), "ACTIVE", 36,
                Instant.parse("2026-04-01T05:59:59Z"));
    }

    private Pair posted() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Pair>> pairs = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<VoucherRequest> req = ArgumentCaptor.forClass(VoucherRequest.class);
        verify(ledger).post(req.capture(), pairs.capture());
        assertThat(req.getValue().creditAccountId()).as("funding names no loan").isNull();
        assertThat(req.getValue().orgUnitCode()).as("funding is the institution's treasury").isEqualTo(FundingPostingService.CORPORATE_UNIT);
        return pairs.getValue().get(0);
    }

    @Test
    @DisplayName("each movement in its accounts: capital to equity, a drawdown to the liability, interest to expense")
    void eachMovementInItsAccounts() {
        String[][] cases = {
                {"CONTRIBUTION", AccountCodes.BANCOS, AccountCodes.CAPITAL_SOCIAL},
                {"DRAWDOWN", AccountCodes.BANCOS, AccountCodes.PRESTAMOS_FONDEO},
                {"INTEREST_ACCRUAL", AccountCodes.GASTO_INTERESES_FONDEO, AccountCodes.INTERESES_FONDEO_POR_PAGAR},
                {"INTEREST_PAYMENT", AccountCodes.INTERESES_FONDEO_POR_PAGAR, AccountCodes.BANCOS},
                {"PRINCIPAL_REPAYMENT", AccountCodes.PRESTAMOS_FONDEO, AccountCodes.BANCOS}};
        for (String[] c : cases) {
            reset(ledger, balances, deferrals);
            service.onMovement(fact(c[0], "21666.67", "2000000", "21666.67", "0"));
            Pair p = posted();
            assertThat(p.debitAccount()).as(c[0]).isEqualTo(c[1]);
            assertThat(p.creditAccount()).as(c[0]).isEqualTo(c[2]);
            assertThat(p.amount()).isEqualByComparingTo("21666.67");
        }
    }

    @Test
    @DisplayName("the line's opening fee is a transaction cost: deducted from the liability and amortized over its term")
    void openingFeeIsATransactionCost() {
        service.onMovement(fact("OPENING_FEE", "25000", "0", "0", "0"));

        Pair p = posted();
        assertThat(p.debitAccount()).isEqualTo(AccountCodes.COSTOS_FONDEO_POR_AMORTIZAR);
        assertThat(p.creditAccount()).isEqualTo(AccountCodes.BANCOS);
        verify(deferrals).deferFunding(any(), eq(source), eq(FundingPostingService.CORPORATE_UNIT),
                eq(new BigDecimal("25000")), eq(36), eq("202603"));
    }

    @Test
    @DisplayName("the balances treasury reported are kept for the close")
    void balancesKept() {
        service.onMovement(fact("INTEREST_ACCRUAL", "100", "2000000", "100", "0"));

        verify(balances).record(eq(source), eq("CREDIT_LINE"), any(), any(), eq(new BigDecimal("0")),
                eq(new BigDecimal("2000000")), eq(new BigDecimal("100")), eq(LocalDate.of(2026, 3, 31)), eq("ACTIVE"), any());
    }

    @Test
    @DisplayName("an unknown movement is not booked")
    void unknownMovement() {
        service.onMovement(fact("SOMETHING_ELSE", "100", "0", "0", "0"));
        verify(ledger, never()).post(any(), any());
    }
}

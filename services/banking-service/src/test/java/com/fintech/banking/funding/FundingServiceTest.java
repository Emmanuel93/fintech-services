package com.fintech.banking.funding;

import com.fintech.banking.application.port.out.BankAccountRepository;
import com.fintech.banking.application.port.out.FundingEventPublisher;
import com.fintech.banking.application.port.out.FundingRepository;
import com.fintech.banking.application.service.FundingService;
import com.fintech.banking.application.service.FundingService.CapitalCommand;
import com.fintech.banking.application.service.FundingService.CreditLineCommand;
import com.fintech.banking.application.service.FundingService.MovementCommand;
import com.fintech.banking.application.service.InternalMovementProjector;
import com.fintech.banking.domain.BankAccount;
import com.fintech.banking.domain.FundingMovement;
import com.fintech.banking.domain.FundingMovement.Type;
import com.fintech.banking.domain.FundingRuleException;
import com.fintech.banking.domain.FundingSource;
import com.fintech.banking.domain.FundingSource.CounterpartyType;
import com.fintech.banking.domain.InternalMovement;
import com.fintech.shared.time.BusinessClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Funding: a capital contribution within what the meeting agreed, a line drawn within its limit, its interest accrued
 * day by day on what was drawn — before every change of principal and before every payment — and the identification of
 * the counterparty the regulation asks for.
 */
class FundingServiceTest {

    static class Memory implements FundingRepository {
        final Map<UUID, FundingSource> sources = new HashMap<>();
        final List<FundingMovement> movements = new ArrayList<>();
        public FundingSource save(FundingSource s) { sources.put(s.getId(), s); return s; }
        public Optional<FundingSource> find(UUID id) { return Optional.ofNullable(sources.get(id)); }
        public List<FundingSource> findAll() { return List.copyOf(sources.values()); }
        public FundingMovement save(FundingMovement m) { movements.add(m); return m; }
        public List<FundingMovement> movementsOf(UUID id) {
            return movements.stream().filter(m -> m.getFundingSourceId().equals(id)).toList();
        }
        public Optional<FundingMovement> findMovement(UUID id, Type type, String reference) {
            return movements.stream().filter(m -> m.getFundingSourceId().equals(id) && m.getType() == type
                    && m.getReference().equals(reference)).findFirst();
        }
    }

    private final Memory repo = new Memory();
    private final BankAccountRepository accounts = mock(BankAccountRepository.class);
    private final List<FundingMovement> published = new ArrayList<>();
    private final List<InternalMovement> projected = new ArrayList<>();
    private final FundingEventPublisher events = (s, m) -> published.add(m);
    private final InternalMovementProjector projector = projected::add;
    private final FundingService service = new FundingService(repo, accounts, events, projector);
    private final UUID account = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        when(accounts.findById(any())).thenReturn(Optional.empty());
        when(accounts.findById(account)).thenReturn(Optional.of(mock(BankAccount.class)));
        BusinessClock.current().shiftTo(LocalDate.of(2026, 12, 31));
    }

    @AfterEach
    void realClock() {
        BusinessClock.current().resetShift();
    }

    private FundingSource line() {
        return service.registerCreditLine(new CreditLineCommand(CounterpartyType.DEVELOPMENT_BANK,
                "Banco de Fomento de Ejemplo", "BFE850101AB1", "CONTRATO-LF-2026-001", account,
                new BigDecimal("5000000"), new BigDecimal("0.13"), new BigDecimal("25000"),
                LocalDate.of(2026, 3, 1), LocalDate.of(2029, 3, 1)), "tesoreria");
    }

    private List<FundingMovement> record(FundingSource s, Type type, String amount, LocalDate on, String ref) {
        return service.record(s.getId(), new MovementCommand(type, amount == null ? null : new BigDecimal(amount), on, ref),
                "tesoreria");
    }

    @Test
    @DisplayName("capital: the contribution is identified and never exceeds what the shareholders' meeting agreed")
    void capitalWithinWhatWasAgreed() {
        FundingSource capital = service.registerCapital(new CapitalCommand("Accionistas fundadores", "KHO200101AB1",
                "ACTA-AGE-2026-02-15", account, new BigDecimal("4000000"), LocalDate.of(2026, 2, 15)), "tesoreria");

        record(capital, Type.CONTRIBUTION, "3000000", LocalDate.of(2026, 3, 2), "SPEI-CAP-1");

        assertThat(capital.getContributed()).isEqualByComparingTo("3000000");
        assertThatThrownBy(() -> record(capital, Type.CONTRIBUTION, "1500000", LocalDate.of(2026, 3, 3), "SPEI-CAP-2"))
                .isInstanceOf(FundingRuleException.class).hasMessageContaining("asamblea");
        assertThat(projected).singleElement().satisfies(m -> {
            assertThat(m.getDirection()).isEqualTo("CREDIT");
            assertThat(m.getMovementType()).isEqualTo("FUNDING");
        });
    }

    @Test
    @DisplayName("a line accrues interest every day on what was drawn: actual days over 360, accrued before each change")
    void interestAccruesOnWhatWasDrawn() {
        FundingSource line = line();
        record(line, Type.DRAWDOWN, "2000000", LocalDate.of(2026, 3, 2), "SPEI-LF-1");
        // 2,000,000 from 2 Mar to 31 Mar: 30 days at 13 % / 360 = 21,666.67.
        List<FundingMovement> accruals = service.accrueAll(LocalDate.of(2026, 3, 31), "cierre");
        assertThat(accruals).singleElement().satisfies(m -> {
            assertThat(m.getAmount()).isEqualByComparingTo("21666.67");
            assertThat(m.getAccruedFrom()).isEqualTo(LocalDate.of(2026, 3, 2));
            assertThat(m.getAccruedThrough()).isEqualTo(LocalDate.of(2026, 3, 31));
        });
        // Accruing again through the same day produces nothing.
        assertThat(service.accrueAll(LocalDate.of(2026, 3, 31), "cierre")).isEmpty();

        // A second drawdown on 16 Apr: the 15 days before it accrue on 2,000,000, then 3,000,000 applies.
        List<FundingMovement> made = record(line, Type.DRAWDOWN, "1000000", LocalDate.of(2026, 4, 16), "SPEI-LF-2");
        assertThat(made).extracting(FundingMovement::getType).containsExactly(Type.INTEREST_ACCRUAL, Type.DRAWDOWN);
        assertThat(made.get(0).getAmount()).isEqualByComparingTo("10833.33");
        assertThat(line.getPrincipal()).isEqualByComparingTo("3000000");

        // Paying the interest on 30 Apr accrues through that day first (15 days on 3,000,000 = 16,250).
        List<FundingMovement> payment = record(line, Type.INTEREST_PAYMENT, "48750.00", LocalDate.of(2026, 4, 30), "SPEI-INT-04");
        assertThat(payment.get(0).getAmount()).isEqualByComparingTo("16250.00");
        assertThat(line.getInterestPayable()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("a line is drawn within its limit and its term; its opening fee is paid once and as agreed")
    void lineLimits() {
        FundingSource line = line();
        assertThatThrownBy(() -> record(line, Type.DRAWDOWN, "6000000", LocalDate.of(2026, 3, 2), "X"))
                .isInstanceOf(FundingRuleException.class).hasMessageContaining("línea autorizada");
        assertThatThrownBy(() -> record(line, Type.DRAWDOWN, "1000", LocalDate.of(2026, 2, 27), "Y"))
                .isInstanceOf(FundingRuleException.class).hasMessageContaining("anterior");
        assertThatThrownBy(() -> record(line, Type.OPENING_FEE, "24000", LocalDate.of(2026, 3, 2), "FEE"))
                .isInstanceOf(FundingRuleException.class).hasMessageContaining("25000");
        record(line, Type.OPENING_FEE, null, LocalDate.of(2026, 3, 2), "FEE-OK");
        assertThatThrownBy(() -> record(line, Type.OPENING_FEE, null, LocalDate.of(2026, 3, 3), "FEE-2"))
                .isInstanceOf(FundingRuleException.class).hasMessageContaining("ya se pagó");
    }

    @Test
    @DisplayName("an instruction recorded twice is one movement; a value date after today is refused")
    void idempotentAndDatedByTheMoney() {
        FundingSource line = line();
        record(line, Type.DRAWDOWN, "500000", LocalDate.of(2026, 3, 2), "SPEI-LF-9");
        record(line, Type.DRAWDOWN, "500000", LocalDate.of(2026, 3, 2), "SPEI-LF-9");

        assertThat(line.getPrincipal()).isEqualByComparingTo("500000");
        assertThat(published).hasSize(1);
        assertThatThrownBy(() -> record(line, Type.DRAWDOWN, "1", LocalDate.of(2027, 1, 1), "FUT"))
                .isInstanceOf(FundingRuleException.class).hasMessageContaining("posterior a hoy");
    }

    @Test
    @DisplayName("the counterparty is identified: a source without a valid RFC or without its document is refused")
    void counterpartyIdentified() {
        assertThatThrownBy(() -> service.registerCapital(new CapitalCommand("Accionistas", "SIN-RFC", "ACTA-1", account,
                BigDecimal.TEN, LocalDate.of(2026, 1, 1)), "t"))
                .isInstanceOf(FundingRuleException.class).hasMessageContaining("RFC");
        assertThatThrownBy(() -> service.registerCapital(new CapitalCommand("Accionistas", "KHO200101AB1", " ", account,
                BigDecimal.TEN, LocalDate.of(2026, 1, 1)), "t"))
                .isInstanceOf(FundingRuleException.class).hasMessageContaining("acta");
        assertThatThrownBy(() -> service.registerCapital(new CapitalCommand("Accionistas", "KHO200101AB1", "ACTA-1",
                UUID.randomUUID(), BigDecimal.TEN, LocalDate.of(2026, 1, 1)), "t"))
                .isInstanceOf(FundingRuleException.class).hasMessageContaining("cuenta propia");
    }

    @Test
    @DisplayName("a line repaid in full owes nothing and can be drawn again until it matures")
    void repaidLineCanBeDrawnAgain() {
        FundingSource line = line();
        record(line, Type.DRAWDOWN, "100000", LocalDate.of(2026, 3, 2), "D1");
        record(line, Type.INTEREST_PAYMENT, "361.11", LocalDate.of(2026, 3, 11), "I1");   // 10 days: 361.11
        record(line, Type.PRINCIPAL_REPAYMENT, "100000", LocalDate.of(2026, 3, 12), "P1");
        // The day before the repayment (11 Mar) was already accrued with the payment: nothing is left owed.
        assertThat(line.getInterestPayable()).isEqualByComparingTo("0");
        assertThat(line.getPrincipal()).isEqualByComparingTo("0");
        assertThat(line.getStatus()).isEqualTo("ACTIVE");
        record(line, Type.DRAWDOWN, "50000", LocalDate.of(2026, 4, 1), "D2");
        assertThat(line.getPrincipal()).isEqualByComparingTo("50000");
    }

    @Test
    @DisplayName("performance: a year of daily accruals on a line computes in milliseconds")
    void accrualPerformance() {
        FundingSource line = line();
        record(line, Type.DRAWDOWN, "3000000", LocalDate.of(2026, 3, 2), "D");
        long start = System.nanoTime();
        LocalDate day = LocalDate.of(2026, 3, 2);
        BigDecimal total = BigDecimal.ZERO;
        for (int i = 0; i < 300; i++, day = day.plusDays(1)) {
            for (FundingMovement m : service.accrueAll(day, "noche")) total = total.add(m.getAmount());
        }
        assertThat((System.nanoTime() - start) / 1_000_000).as("ms").isLessThan(2_000L);
        // Daily rounding stays within cents of the whole period: 3,000,000 × 0.13 × 300 / 360 = 325,000.
        assertThat(total.subtract(new BigDecimal("325000")).abs()).isLessThan(new BigDecimal("2.00"));
    }
}

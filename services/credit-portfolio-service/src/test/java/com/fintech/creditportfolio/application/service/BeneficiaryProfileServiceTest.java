package com.fintech.creditportfolio.application.service;

import com.fintech.creditportfolio.application.port.out.BalanceEventRepository;
import com.fintech.creditportfolio.application.port.out.BeneficiaryFlagRepository;
import com.fintech.creditportfolio.application.port.out.CreditAccountRepository;
import com.fintech.creditportfolio.application.port.out.DispositionRepository;
import com.fintech.creditportfolio.application.port.out.InstallmentRepository;
import com.fintech.creditportfolio.application.port.out.PlacementPolicyCopyRepository;
import com.fintech.creditportfolio.domain.CreditAccount;
import com.fintech.creditportfolio.domain.Disposition;
import com.fintech.creditportfolio.domain.DispositionType;
import com.fintech.creditportfolio.domain.Installment;
import com.fintech.creditportfolio.domain.InstallmentStatus;
import com.fintech.creditportfolio.domain.PlacementGate;
import com.fintech.creditportfolio.domain.beneficiary.BeneficiaryFlag;
import com.fintech.creditportfolio.domain.beneficiary.ClientLimits;
import com.fintech.creditportfolio.domain.beneficiary.ClientType;
import com.fintech.creditportfolio.domain.beneficiary.FlagReason;
import com.fintech.creditportfolio.domain.beneficiary.FlagSource;
import com.fintech.creditportfolio.domain.beneficiary.PlacementPolicyCopy;
import com.fintech.creditportfolio.domain.beneficiary.ReportReason;
import com.fintech.shared.time.BusinessClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class BeneficiaryProfileServiceTest {

    @Mock DispositionRepository dispositions;
    @Mock InstallmentRepository installments;
    @Mock CreditAccountRepository accounts;
    @Mock BalanceEventRepository ledger;
    @Mock BeneficiaryFlagRepository flags;
    @Mock PlacementPolicyCopyRepository copies;
    @Mock PlacementGateService gates;

    BeneficiaryProfileService service;

    final UUID client = UUID.randomUUID();
    CreditAccount line;
    final List<Disposition> his = new ArrayList<>();
    final List<Installment> plans = new ArrayList<>();
    final List<BeneficiaryFlag> stored = new ArrayList<>();
    final Map<String, PlacementPolicyCopy> policyByUnit = new HashMap<>();

    @BeforeEach
    void setUp() {
        service = new BeneficiaryProfileService(dispositions, installments, accounts, ledger, flags,
                new PlacementPolicyCopyService(copies), gates);
        line = CreditAccount.fromSnapshot(UUID.randomUUID(), "CTR-DL-9", UUID.randomUUID(),
                "DL-002", 1, "DISTRIBUTOR_LINE", "REVOLVING", null, new BigDecimal("100000"), null,
                new BigDecimal("0.95"), new BigDecimal("0.36"), null, BigDecimal.ZERO, "032180000118359719", "MEDIO", null, null);
        line.activate(BigDecimal.ZERO);
        line.sealOriginUnit("S_TIJ");
        lenient().when(dispositions.findByBeneficiaryPartyId(client)).thenReturn(his);
        lenient().when(accounts.findByCreditAccountIdIn(anyCollection())).thenReturn(List.of(line));
        lenient().when(installments.findByScheduleIds(anyCollection())).thenReturn(plans);
        lenient().when(ledger.paymentsOf(any())).thenReturn(List.of());
        lenient().when(gates.evaluate(any())).thenReturn(PlacementGate.OPEN);
        lenient().when(flags.findActiveByBeneficiary(client)).thenAnswer(inv -> stored.stream().filter(BeneficiaryFlag::isActive).toList());
        lenient().when(flags.save(any())).thenAnswer(inv -> {
            BeneficiaryFlag f = inv.getArgument(0);
            if (!stored.contains(f)) stored.add(f);
            return f;
        });
        lenient().when(flags.findById(any())).thenAnswer(inv -> stored.stream().filter(f -> f.getFlagId().equals(inv.getArgument(0))).findFirst());
        lenient().when(copies.findByUnitCode(any())).thenAnswer(inv -> Optional.ofNullable(policyByUnit.get((String) inv.getArgument(0))));
        lenient().when(copies.findByPartyRef(any())).thenAnswer(inv -> policyByUnit.values().stream()
                .filter(c -> inv.getArgument(0).equals(c.getPartyRef())).findFirst());
    }

    void branchPolicy(String unit, UUID partyRef, ClientLimits nuevo, ClientLimits revolvente) {
        policyByUnit.put(unit, PlacementPolicyCopy.of(unit).overwrite(UUID.randomUUID(), "MX.R.Z." + unit,
                partyRef == null ? "BRANCH" : "DISTRIBUTOR", partyRef, true, unit, nuevo, revolvente, "evt-" + unit, BusinessClock.current().now()));
    }

    void livePlacement(String amount, int n, LocalDate firstDue, int billed) {
        Disposition d = Disposition.create(line.getCreditAccountId(), DispositionType.THIRD_PARTY_CREDIT, new BigDecimal(amount), client);
        d.markProcessing();
        d.complete("SPEI-9");
        his.add(d);
        BigDecimal principal = new BigDecimal(amount).divide(BigDecimal.valueOf(n), 2, RoundingMode.HALF_UP);
        for (int i = 1; i <= n; i++) {
            Installment c = Installment.of(d.getDispositionId(), i, firstDue.plusDays(15L * (i - 1)), principal, new BigDecimal("40"));
            if (i <= billed) c.billIn(i, c.getDueDate());
            plans.add(c);
        }
    }

    static final ClientLimits NUEVO = new ClientLimits(new BigDecimal("5000"), new BigDecimal("5000"), 1, 16);
    static final ClientLimits REVOLVENTE = new ClientLimits(new BigDecimal("10000"), new BigDecimal("15000"), 3, 24);

    @Test
    @DisplayName("un cliente nuevo bajo la política de la sucursal: monto, plazo y préstamos vivos")
    void aNewClientUnderTheBranchPolicy() {
        branchPolicy("S_TIJ", null, NUEVO, REVOLVENTE);

        assertThat(service.reject(line, client, new BigDecimal("5000"), 16)).isNull();
        assertThat(service.reject(line, client, new BigDecimal("8000"), 16)).startsWith("AMOUNT_ABOVE_CLIENT_LIMIT");
        assertThat(service.reject(line, client, new BigDecimal("5000"), 18)).startsWith("TERM_ABOVE_CLIENT_LIMIT");

        livePlacement("3000", 12, BusinessClock.current().today().plusDays(10), 0);
        var view = service.limitsFor(line, client, new BigDecimal("1000"), 12);
        assertThat(view.clientType()).isEqualTo(ClientType.NUEVO);
        assertThat(view.activeLoans()).isEqualTo(1);
        assertThat(view.canPlace()).isFalse();
        assertThat(view.reason()).startsWith("CLIENT_ACTIVE_LOANS_LIMIT");
        assertThat(view.inheritedFrom()).isEqualTo("S_TIJ");
        assertThat(view.loansAvailable()).isZero();
    }

    @Test
    @DisplayName("la política propia de la distribuidora (su nodo) manda sobre la de su sucursal")
    void herOwnNodeWinsOverHerBranch() {
        branchPolicy("S_TIJ", null, NUEVO, REVOLVENTE);
        branchPolicy("D_ANA", line.getObligorPartyId(), new ClientLimits(new BigDecimal("3000"), new BigDecimal("5000"), 1, 12), REVOLVENTE);

        assertThat(service.reject(line, client, new BigDecimal("4000"), 12)).startsWith("AMOUNT_ABOVE_CLIENT_LIMIT: cliente NUEVO hasta 3000");
        assertThat(service.limitsFor(line, client, null, null).inheritedFrom()).isEqualTo("D_ANA");
    }

    @Test
    @DisplayName("sin política configurada sólo cuenta la lista roja")
    void withoutAPolicyOnlyTheRedListApplies() {
        var view = service.limitsFor(line, client, new BigDecimal("10000"), 24);
        assertThat(view.policyConfigured()).isFalse();
        assertThat(view.canPlace()).isTrue();
        assertThat(view.amountAvailable()).isNull();
    }

    @Test
    @DisplayName("un cliente reportado no recibe colocaciones de nadie; levantada la marca, vuelve a poder")
    void aReportedClientGetsNothingUntilTheMarkIsCleared() {
        UUID her = line.getObligorPartyId();
        BeneficiaryFlag report = service.report(client, FlagSource.DISTRIBUTOR, her, line.getCreditAccountId(),
                ReportReason.REFUSES_TO_PAY, "Dice que ya no va a pagar", her.toString());

        assertThat(service.reject(line, client, new BigDecimal("1000"), 12)).isEqualTo("CLIENT_RED_LISTED: DISTRIBUTOR_REPORT");
        var profile = service.profile(client);
        assertThat(profile.isRedListed()).isTrue();
        assertThat(profile.flags()).singleElement().satisfies(f -> {
            assertThat(f.reason()).isEqualTo(FlagReason.DISTRIBUTOR_REPORT);
            assertThat(f.detail()).isEqualTo("REFUSES_TO_PAY");
            assertThat(f.comment()).isEqualTo("Dice que ya no va a pagar");
            assertThat(f.flagId()).isEqualTo(report.getFlagId());
        });

        service.clear(report.getFlagId(), "backoffice-1", "Se aclaró con la distribuidora");
        assertThat(service.reject(line, client, new BigDecimal("1000"), 12)).isNull();
        assertThat(report.getClearComment()).isEqualTo("Se aclaró con la distribuidora");
    }

    @Test
    @DisplayName("si su distribuidora está en mora y su cuota quedó sin pagar, sale la advertencia y no se le presta")
    void hisDistributorsArrearsReachHim_untilHisInstallmentIsPaid() {
        line.updateDelinquency(4);
        livePlacement("4000", 12, BusinessClock.current().today().minusDays(4), 1);

        assertThat(service.reject(line, client, new BigDecimal("1000"), 12)).isEqualTo("CLIENT_RED_LISTED: DISTRIBUTOR_IN_ARREARS");

        plans.getFirst().settleAs(InstallmentStatus.PAID);
        assertThat(service.profile(client).isRedListed()).isFalse();
    }

    @Test
    @DisplayName("varios clientes a la vez, uno por cada id pedido")
    void profilesInBatch() {
        UUID other = UUID.randomUUID();
        lenient().when(dispositions.findByBeneficiaryPartyId(other)).thenReturn(List.of());
        assertThat(service.profiles(List.of(client, other, client))).extracting(p -> p.beneficiaryPartyId()).containsExactly(client, other);
    }
}

package com.fintech.creditportfolio.application.service;

import com.fintech.creditportfolio.application.ClientPlacementCheck;
import com.fintech.creditportfolio.application.port.out.BalanceEventRepository;
import com.fintech.creditportfolio.application.port.out.BeneficiaryFlagRepository;
import com.fintech.creditportfolio.application.port.out.CreditAccountRepository;
import com.fintech.creditportfolio.application.port.out.DispositionRepository;
import com.fintech.creditportfolio.application.port.out.InstallmentRepository;
import com.fintech.creditportfolio.domain.CreditAccount;
import com.fintech.creditportfolio.domain.CreditAccountStatus;
import com.fintech.creditportfolio.domain.Disposition;
import com.fintech.creditportfolio.domain.Installment;
import com.fintech.creditportfolio.domain.PlacementGate;
import com.fintech.creditportfolio.domain.StatementCoverage;
import com.fintech.creditportfolio.domain.beneficiary.BeneficiaryFlag;
import com.fintech.creditportfolio.domain.beneficiary.BeneficiaryProfile;
import com.fintech.creditportfolio.domain.beneficiary.BeneficiaryTyping;
import com.fintech.creditportfolio.domain.beneficiary.ClientLimits;
import com.fintech.creditportfolio.domain.beneficiary.ClientType;
import com.fintech.creditportfolio.domain.beneficiary.FlagSource;
import com.fintech.creditportfolio.domain.beneficiary.PlacementPolicyCopy;
import com.fintech.creditportfolio.domain.beneficiary.ReportReason;
import com.fintech.shared.time.BusinessClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A distributor's client as the portfolio sees him: his type, his red-list marks, what he owes on each line, and
 * whether a new placement fits the branch's limits for him.
 *
 * <p>The red list is the rule, not only the warning: with any active mark he receives no placement from any
 * distributor ({@code CLIENT_RED_LISTED}). The derived marks lift themselves the day he or his distributor is
 * current; a report is cleared by who put it or by the backoffice, with a comment.
 */
@Service
@Transactional(readOnly = true)
public class BeneficiaryProfileService implements ClientPlacementCheck {

    public static final String CLIENT_RED_LISTED = "CLIENT_RED_LISTED";

    private static final Logger log = LoggerFactory.getLogger(BeneficiaryProfileService.class);

    private final DispositionRepository dispositions;
    private final InstallmentRepository installments;
    private final CreditAccountRepository accounts;
    private final BalanceEventRepository ledger;
    private final BeneficiaryFlagRepository flags;
    private final PlacementPolicyCopyService policies;
    private final PlacementGateService gates;

    public BeneficiaryProfileService(DispositionRepository dispositions, InstallmentRepository installments,
                                     CreditAccountRepository accounts, BalanceEventRepository ledger,
                                     BeneficiaryFlagRepository flags, PlacementPolicyCopyService policies,
                                     PlacementGateService gates) {
        this.dispositions = dispositions;
        this.installments = installments;
        this.accounts = accounts;
        this.ledger = ledger;
        this.flags = flags;
        this.policies = policies;
        this.gates = gates;
    }

    public BeneficiaryProfile profile(UUID beneficiaryPartyId) {
        List<Disposition> his = dispositions.findByBeneficiaryPartyId(beneficiaryPartyId);
        Map<UUID, CreditAccount> lines = his.isEmpty() ? Map.of() : accounts.findByCreditAccountIdIn(
                his.stream().map(Disposition::getCreditAccountId).distinct().toList()).stream()
                .collect(Collectors.toMap(CreditAccount::getCreditAccountId, Function.identity(), (a, b) -> a));
        Map<UUID, List<Installment>> plans = his.isEmpty() ? Map.of() : installments.findByScheduleIds(
                his.stream().map(Disposition::getDispositionId).toList()).stream()
                .collect(Collectors.groupingBy(Installment::getScheduleId));
        Map<UUID, BeneficiaryTyping.LineFacts> facts = new HashMap<>();
        List<BeneficiaryTyping.PlacementFacts> placements = new ArrayList<>();
        for (Disposition d : his) {
            CreditAccount line = lines.get(d.getCreditAccountId());
            BeneficiaryTyping.LineFacts f = facts.computeIfAbsent(d.getCreditAccountId(), id -> lineFacts(id, line));
            placements.add(new BeneficiaryTyping.PlacementFacts(d, plans.getOrDefault(d.getDispositionId(), List.of()), f));
        }
        Instant now = BusinessClock.current().now();
        LocalDate today = BusinessClock.current().today();
        BeneficiaryTyping.Standing standing = BeneficiaryTyping.evaluate(beneficiaryPartyId, placements, today, now);

        List<BeneficiaryProfile.FlagView> marks = new ArrayList<>(standing.derivedFlags());
        for (BeneficiaryFlag f : flags.findActiveByBeneficiary(beneficiaryPartyId)) {
            marks.add(view(f));
        }
        return new BeneficiaryProfile(beneficiaryPartyId, standing.type(), List.copyOf(marks), standing.loansGranted(),
                standing.loansPaid(), standing.activeLoans(), standing.outstanding(), standing.lines());
    }

    private BeneficiaryTyping.LineFacts lineFacts(UUID creditAccountId, CreditAccount line) {
        if (line == null) return new BeneficiaryTyping.LineFacts(creditAccountId, null, false, false, false, null);
        // Quebranto and quita are not payment: nothing paid on such a line makes him REVOLVENTE.
        boolean excluded = line.getStatus() == CreditAccountStatus.WRITTEN_OFF
                || CreditAccount.CLOSED_AFTER_QUITA.equals(line.getClosureReason())
                || StatementCoverage.quitaOn(ledger.paymentsOf(creditAccountId)) != null;
        PlacementGate gate = line.getStatus() == CreditAccountStatus.ACTIVE && gates != null ? gates.evaluate(line) : PlacementGate.OPEN;
        boolean behind = gate.blocked() && gate.overdueAmount() != null && gate.overdueAmount().signum() > 0
                || line.getDaysDelinquent() > 0
                || line.getStatus() == CreditAccountStatus.WRITTEN_OFF
                || line.getStatus() == CreditAccountStatus.RESTRUCTURED
                || line.getStatus() == CreditAccountStatus.SUSPENDED;
        String reason = gate.reason() != null ? gate.reason()
                : line.getStatus() == CreditAccountStatus.ACTIVE ? null : "LINE_" + line.getStatus();
        return new BeneficiaryTyping.LineFacts(creditAccountId, line.getObligorPartyId(), excluded, behind, gate.blocked(), reason);
    }

    // ── Limits for a placement ────────────────────────────────────────────────────────────────

    /** What this client may take on this line today: his type, the limits that apply, his headroom, and why not. */
    public PlacementLimitsView limitsFor(CreditAccount line, UUID beneficiaryPartyId, BigDecimal amount, Integer termPeriods) {
        BeneficiaryProfile profile = profile(beneficiaryPartyId);
        Optional<PlacementPolicyCopy> policy = policies.forAccount(line);
        ClientLimits limits = policy.map(p -> p.limitsFor(profile.type())).orElse(ClientLimits.NONE);
        BeneficiaryProfile.LineStanding onLine = profile.on(line.getCreditAccountId());
        List<String> reasons = new ArrayList<>();
        if (profile.isRedListed()) {
            reasons.add(CLIENT_RED_LISTED + ": " + profile.flagSummary());
        }
        if (amount != null || termPeriods != null) {
            reasons.addAll(limits.rejections(profile.type(), amount, termPeriods, onLine.activeLoans(), onLine.outstanding()));
        }
        Integer loansLeft = limits.maxActiveLoans() == null ? null : Math.max(0, limits.maxActiveLoans() - onLine.activeLoans());
        return new PlacementLimitsView(beneficiaryPartyId, profile.type(), policy.isPresent(),
                policy.map(PlacementPolicyCopy::getInheritedFrom).orElse(null), limits,
                onLine.activeLoans(), onLine.outstanding(), limits.amountHeadroom(onLine.outstanding()), loansLeft,
                profile.flags(), reasons.isEmpty(), reasons.isEmpty() ? null : String.join("; ", reasons));
    }

    public record PlacementLimitsView(UUID beneficiaryPartyId, ClientType clientType, boolean policyConfigured,
                                      String inheritedFrom, ClientLimits limits, int activeLoans, BigDecimal outstanding,
                                      BigDecimal amountAvailable, Integer loansAvailable,
                                      List<BeneficiaryProfile.FlagView> flags, boolean canPlace, String reason) {}

    /** The portfolio's reason to refuse a placement to this client, or null. */
    @Override
    public String reject(CreditAccount line, UUID beneficiaryPartyId, BigDecimal amount, Integer termPeriods) {
        if (beneficiaryPartyId == null) return null;
        PlacementLimitsView view = limitsFor(line, beneficiaryPartyId, amount, termPeriods);
        return view.canPlace() ? null : view.reason();
    }

    // ── Reports ───────────────────────────────────────────────────────────────────────────────

    @Transactional
    public BeneficiaryFlag report(UUID beneficiaryPartyId, FlagSource source, UUID distributorPartyId, UUID creditAccountId,
                                  ReportReason reason, String comment, String raisedBy) {
        BeneficiaryFlag flag = BeneficiaryFlag.report(beneficiaryPartyId, source, distributorPartyId, creditAccountId,
                reason, comment, raisedBy, BusinessClock.current().now());
        BeneficiaryFlag saved = flags.save(flag);
        log.info("Cliente {} reportado a lista roja por {} ({}): {}", beneficiaryPartyId, raisedBy, reason, comment);
        return saved;
    }

    @Transactional
    public BeneficiaryFlag clear(UUID flagId, String clearedBy, String comment) {
        BeneficiaryFlag flag = flags.findById(flagId)
                .orElseThrow(() -> new IllegalArgumentException("No existe la marca " + flagId));
        flag.clear(clearedBy, comment, BusinessClock.current().now());
        log.info("Marca {} del cliente {} levantada por {}: {}", flagId, flag.getBeneficiaryPartyId(), clearedBy, comment);
        return flags.save(flag);
    }

    /** Several clients at once (a distributor's directory): one profile each, in the order asked. */
    public List<BeneficiaryProfile> profiles(List<UUID> beneficiaryPartyIds) {
        return beneficiaryPartyIds.stream().distinct().map(this::profile).toList();
    }

    public List<BeneficiaryFlag> history(UUID beneficiaryPartyId) {
        return flags.findByBeneficiary(beneficiaryPartyId);
    }

    static BeneficiaryProfile.FlagView view(BeneficiaryFlag f) {
        return new BeneficiaryProfile.FlagView(f.getFlagId(), f.getReason(), f.getSource(), f.getRaisedAt(),
                f.getDistributorPartyId(), f.getCreditAccountId(), f.getDetail(), f.getComment());
    }
}

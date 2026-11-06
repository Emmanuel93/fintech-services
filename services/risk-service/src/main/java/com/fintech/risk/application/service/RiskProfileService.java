package com.fintech.risk.application.service;

import com.fintech.risk.application.ProvisionSummary;
import com.fintech.risk.application.port.in.GetProvisionSummaryUseCase;
import com.fintech.risk.application.port.in.GetRiskProfileUseCase;
import com.fintech.risk.application.port.out.RiskProfileRepository;
import com.fintech.risk.domain.Ifrs9Stage;
import com.fintech.risk.domain.RiskProfile;
import com.fintech.risk.domain.RiskProfileNotFoundException;
import com.fintech.risk.domain.RiskProfileStatus;
import com.fintech.risk.application.port.out.RiskEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import com.fintech.shared.time.BusinessClock;

/**
 * Keeps each {@link RiskProfile} in sync with credit-portfolio's activation/balance/delinquency
 * signals and Collections' restructure signal. Reactive sync only — never transitions the stage
 * here (RC-03); the nightly {@code RiskAssessmentService} owns stage transitions.
 */
@Service
@Transactional
public class RiskProfileService implements GetRiskProfileUseCase, GetProvisionSummaryUseCase {

    private static final Logger log = LoggerFactory.getLogger(RiskProfileService.class);

    private final RiskProfileRepository repository;
    private final RiskEventPublisher eventPublisher;

    public RiskProfileService(RiskProfileRepository repository, RiskEventPublisher eventPublisher) {
        this.repository = repository;
        this.eventPublisher = eventPublisher;
    }

    @Override
    @Transactional(readOnly = true)
    public RiskProfile getByCreditAccountId(UUID creditAccountId) {
        return repository.findByCreditAccountId(creditAccountId)
                .orElseThrow(() -> new RiskProfileNotFoundException(creditAccountId.toString()));
    }

    @Override
    @Transactional(readOnly = true)
    public List<RiskProfile> getByPartyId(UUID obligorPartyId) {
        return repository.findByObligorPartyId(obligorPartyId);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<RiskProfile> search(UUID partyId, String productType, Ifrs9Stage stage,
                                    RiskProfileStatus status, Pageable pageable) {
        String type = (productType == null || productType.isBlank()) ? null : productType;
        return repository.search(partyId, type, stage, status, pageable);
    }

    @Override
    @Transactional(readOnly = true)
    public List<RiskProfile> findByCreditAccountIds(Collection<UUID> creditAccountIds) {
        if (creditAccountIds == null || creditAccountIds.isEmpty()) {
            return List.of();
        }
        return repository.findByCreditAccountIdIn(creditAccountIds);
    }

    /** CreditAccountActivated → create RiskProfile (STAGE_1/CURRENT). Idempotent. */
    public void onCreditAccountActivated(UUID creditAccountId, UUID obligorPartyId, String productType) {
        if (repository.existsByCreditAccountId(creditAccountId)) return;
        RiskProfile profile = RiskProfile.create(creditAccountId, obligorPartyId, productType);
        repository.save(profile);
        log.info("RiskProfile created creditAccountId={} productType={}", creditAccountId, productType);
    }

    /**
     * BalanceUpdated → snapshot ead (PR-02). accountStatus SETTLED/WRITTEN_OFF closes the profile
     * (ES-03) — credit-portfolio has no dedicated ProductSettled/ProductWrittenOff event, so both are
     * derived from BalanceUpdated.accountStatus.
     */
    public void onBalanceUpdated(UUID creditAccountId, BigDecimal totalDebt, String accountStatus) {
        onBalanceUpdated(creditAccountId, totalDebt, null, accountStatus);
    }

    /**
     * A settled loan's reserve goes to zero and the change is published, so accounting releases it. A written-off
     * one only closes: accounting consumes the reserve with the write-off itself and releases what is left in the
     * same voucher (publishing a zero here could reach accounting before the write-off and send the whole loss to
     * expense instead of the reserve).
     */
    public void onBalanceUpdated(UUID creditAccountId, BigDecimal totalDebt, BigDecimal availableCredit,
                                 String accountStatus) {
        repository.findByCreditAccountId(creditAccountId).ifPresentOrElse(profile -> {
            // A credit cancelled because its disbursement never happened (D63) closes like a settled one: it never
            // lent anything, so it keeps no reserve, and accounting releases what it had provisioned.
            // A line closed after a quita (the holder settled what was left) ends like a settled loan: nothing exposed.
            if ("SETTLED".equals(accountStatus) || "CANCELLED".equals(accountStatus) || "CLOSED".equals(accountStatus)) {
                if (profile.getStatus().isClosed()) return;
                boolean teniaReserva = profile.getProvisionAmount().signum() != 0;
                profile.settle(BusinessClock.current().now());
                repository.save(profile);
                if (teniaReserva) {
                    eventPublisher.publishRiskAssessmentUpdated(profile, false);
                }
                log.info("RiskProfile CLOSED ({}, reserva a cero) creditAccountId={}",
                        "CANCELLED".equals(accountStatus) ? "cancelado" : "CLOSED".equals(accountStatus) ? "línea cerrada" : "liquidado",
                        creditAccountId);
                return;
            }
            if ("WRITTEN_OFF".equals(accountStatus)) {
                // Closed with no reserve left: the write-off consumed it in accounting. Kept at its last figure, the
                // closed profile disagreed with accounting by the whole reserve of every written-off loan.
                profile.writeOff(BusinessClock.current().now());
                log.info("RiskProfile CLOSED (quebranto, reserva consumida) creditAccountId={}", creditAccountId);
            } else {
                profile.syncExposure(totalDebt, availableCredit);
            }
            repository.save(profile);
        }, () -> log.debug("BalanceUpdated for unknown creditAccountId={} — no profile yet, skipping", creditAccountId));
    }

    /** DelinquencyStatusUpdated → sync days + local bucket (RC-01). days=0 is the "cleared" signal. */
    public void onDelinquencyStatusUpdated(UUID creditAccountId, int daysDelinquent) {
        repository.findByCreditAccountId(creditAccountId).ifPresentOrElse(profile -> {
            profile.syncDaysDelinquent(daysDelinquent);
            repository.save(profile);
        }, () -> log.debug("DelinquencyStatusUpdated for unknown creditAccountId={} — skipping", creditAccountId));
    }

    /** CollectionAgreementExecuted(RESTRUCTURE) → mark forborne, restart cure clock (RC-04). */
    public void onRestructureExecuted(UUID creditAccountId) {
        repository.findByCreditAccountId(creditAccountId).ifPresentOrElse(profile -> {
            profile.markForborne(BusinessClock.current().now());
            repository.save(profile);
            log.info("RiskProfile marked forborne creditAccountId={}", creditAccountId);
        }, () -> log.debug("Restructure for unknown creditAccountId={} — skipping", creditAccountId));
    }

    @Override
    @Transactional(readOnly = true)
    public ProvisionSummary summary() {
        List<RiskProfile> active = repository.findByStatus(RiskProfileStatus.ACTIVE);

        Map<String, List<RiskProfile>> grouped = active.stream()
                .collect(Collectors.groupingBy(p -> p.getProductType() + "|" + p.getIfrs9Stage().name()));

        List<ProvisionSummary.Row> rows = grouped.entrySet().stream()
                .map(e -> {
                    String[] key = e.getKey().split("\\|");
                    BigDecimal ead = e.getValue().stream().map(RiskProfile::getEad).reduce(BigDecimal.ZERO, BigDecimal::add);
                    BigDecimal prov = e.getValue().stream().map(RiskProfile::getProvisionAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
                    return new ProvisionSummary.Row(key[0], key[1], e.getValue().size(), ead, prov);
                })
                .sorted(Comparator.comparing(ProvisionSummary.Row::productType).thenComparing(ProvisionSummary.Row::ifrs9Stage))
                .toList();

        BigDecimal totalProv = active.stream().map(RiskProfile::getProvisionAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalEad  = active.stream().map(RiskProfile::getEad).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new ProvisionSummary(totalProv, totalEad, active.size(), rows);
    }
}

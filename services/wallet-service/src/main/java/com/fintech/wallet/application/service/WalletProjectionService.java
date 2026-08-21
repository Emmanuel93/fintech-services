package com.fintech.wallet.application.service;

import com.fintech.wallet.application.port.in.GetWalletMovementsUseCase;
import com.fintech.wallet.application.port.in.GetWalletViewUseCase;
import com.fintech.wallet.application.port.out.WalletEventPublisher;
import com.fintech.wallet.application.port.out.WalletMovementRepository;
import com.fintech.wallet.application.port.out.WalletViewRepository;
import com.fintech.wallet.domain.WalletMovement;
import com.fintech.wallet.domain.WalletView;
import com.fintech.wallet.domain.WalletViewNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class WalletProjectionService implements GetWalletViewUseCase, GetWalletMovementsUseCase {

    private static final Logger log = LoggerFactory.getLogger(WalletProjectionService.class);

    private final WalletViewRepository walletViewRepository;
    private final WalletMovementRepository movementRepository;
    private final WalletEventPublisher eventPublisher;

    public WalletProjectionService(WalletViewRepository walletViewRepository,
                                    WalletMovementRepository movementRepository,
                                    WalletEventPublisher eventPublisher) {
        this.walletViewRepository = walletViewRepository;
        this.movementRepository   = movementRepository;
        this.eventPublisher       = eventPublisher;
    }

    @Override
    @Transactional(readOnly = true)
    public WalletView getByCreditAccountId(UUID creditAccountId) {
        return walletViewRepository.findByCreditAccountId(creditAccountId)
                .orElseThrow(() -> new WalletViewNotFoundException(creditAccountId.toString()));
    }

    @Override
    @Transactional(readOnly = true)
    public List<WalletView> listByPartyId(UUID obligorPartyId) {
        return walletViewRepository.findByObligorPartyId(obligorPartyId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<WalletMovement> getMovementsByCreditAccountId(UUID creditAccountId) {
        return movementRepository.findByCreditAccountId(creditAccountId);
    }

    public void onCreditAccountActivated(UUID creditAccountId,
                                          UUID obligorPartyId,
                                          String productType,
                                          BigDecimal principalBalance,
                                          BigDecimal availableCredit) {
        if (walletViewRepository.findByCreditAccountId(creditAccountId).isPresent()) {
            log.warn("WalletView already exists for creditAccountId={} — skipping duplicate activation",
                    creditAccountId);
            return;
        }
        WalletView view = WalletView.createFromActivation(
                creditAccountId, obligorPartyId, productType, principalBalance, availableCredit);
        walletViewRepository.save(view);
        log.info("WalletView created walletId={} creditAccountId={}", view.getWalletId(), creditAccountId);
        eventPublisher.publishWalletSnapshotUpdated(view);
    }

    public void onBalanceUpdated(UUID creditAccountId,
                                  UUID obligorPartyId,
                                  BigDecimal principalBalance,
                                  BigDecimal accruedInterestBalance,
                                  BigDecimal penaltyBalance,
                                  BigDecimal availableCredit,
                                  BigDecimal totalDebt,
                                  String accountStatus,
                                  long balanceVersion) {
        // Actualización atómica sólo de columnas de deuda — NO toca walletBalance, así no
        // pisa un crédito de disposición concurrente (fix del lost-update).
        int rows = walletViewRepository.updateBalanceColumns(creditAccountId, principalBalance,
                accruedInterestBalance, penaltyBalance, availableCredit, totalDebt,
                accountStatus, balanceVersion);
        if (rows == 0) {
            log.warn("BalanceUpdated received for unknown creditAccountId={} — creating stub WalletView",
                    creditAccountId);
            walletViewRepository.save(WalletView.createFromActivation(
                    creditAccountId, obligorPartyId, "UNKNOWN", principalBalance, availableCredit));
            walletViewRepository.updateBalanceColumns(creditAccountId, principalBalance,
                    accruedInterestBalance, penaltyBalance, availableCredit, totalDebt,
                    accountStatus, balanceVersion);
        }
        log.debug("WalletView balance synced creditAccountId={} status={} version={}",
                creditAccountId, accountStatus, balanceVersion);
        walletViewRepository.findByCreditAccountId(creditAccountId)
                .ifPresent(eventPublisher::publishWalletSnapshotUpdated);
    }

    /**
     * credit-portfolio.disposition-completed. SELF_USE money stayed on the platform —
     * credit it to walletBalance. THIRD_PARTY_CREDIT/PAYROLL left via external SPEI to
     * someone else's account — the obligor's spendable balance doesn't change (only
     * their debt did, already synced via onBalanceUpdated).
     */
    public void onDispositionCompleted(UUID creditAccountId, BigDecimal amount, String dispositionType) {
        if (!"SELF_USE".equals(dispositionType)) {
            log.debug("DispositionCompleted type={} — not credited to walletBalance creditAccountId={}",
                    dispositionType, creditAccountId);
            return;
        }
        // Incremento atómico de walletBalance — no puede perderse ante un BalanceUpdated concurrente.
        int rows = walletViewRepository.creditWalletBalance(creditAccountId, amount);
        if (rows == 0) {
            log.warn("DispositionCompleted for unknown creditAccountId={} — ignored", creditAccountId);
            return;
        }
        walletViewRepository.findByCreditAccountId(creditAccountId).ifPresent(view -> {
            movementRepository.save(WalletMovement.disposition(
                    creditAccountId, view.getObligorPartyId(), amount, null));
            log.info("WalletView credited creditAccountId={} amount={} walletBalance={}",
                    creditAccountId, amount, view.getWalletBalance());
            eventPublisher.publishWalletSnapshotUpdated(view);
        });
    }

    public void onInstallmentDue(UUID creditAccountId, BigDecimal installmentAmount, LocalDate dueDate) {
        walletViewRepository.findByCreditAccountId(creditAccountId).ifPresentOrElse(
                view -> {
                    view.applyInstallmentDue(installmentAmount, dueDate);
                    walletViewRepository.save(view);
                    log.info("WalletView installment-due applied creditAccountId={} dueDate={}",
                            creditAccountId, dueDate);
                    eventPublisher.publishWalletSnapshotUpdated(view);
                },
                () -> log.warn("InstallmentDue for unknown creditAccountId={} — ignored", creditAccountId));
    }
}

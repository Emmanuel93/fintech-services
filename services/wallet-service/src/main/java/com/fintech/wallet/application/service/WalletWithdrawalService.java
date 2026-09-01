package com.fintech.wallet.application.service;

import com.fintech.wallet.application.WithdrawFromWalletCommand;
import com.fintech.wallet.application.port.in.WithdrawFromWalletUseCase;
import com.fintech.wallet.application.port.out.WalletEventPublisher;
import com.fintech.wallet.application.port.out.WalletMovementRepository;
import com.fintech.wallet.application.port.out.WalletViewRepository;
import com.fintech.wallet.application.port.out.WalletWithdrawalRepository;
import com.fintech.wallet.domain.InsufficientWalletBalanceException;
import com.fintech.wallet.domain.WalletMovement;
import com.fintech.wallet.domain.WalletView;
import com.fintech.wallet.domain.WalletViewNotFoundException;
import com.fintech.wallet.domain.WalletWithdrawal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class WalletWithdrawalService implements WithdrawFromWalletUseCase {

    private static final Logger log = LoggerFactory.getLogger(WalletWithdrawalService.class);

    private final WalletViewRepository walletViewRepository;
    private final WalletWithdrawalRepository withdrawalRepository;
    private final WalletMovementRepository movementRepository;
    private final WalletEventPublisher eventPublisher;

    public WalletWithdrawalService(WalletViewRepository walletViewRepository,
                                    WalletWithdrawalRepository withdrawalRepository,
                                    WalletMovementRepository movementRepository,
                                    WalletEventPublisher eventPublisher) {
        this.walletViewRepository  = walletViewRepository;
        this.withdrawalRepository  = withdrawalRepository;
        this.movementRepository    = movementRepository;
        this.eventPublisher        = eventPublisher;
    }

    @Override
    public WalletWithdrawal withdraw(WithdrawFromWalletCommand cmd) {
        WalletView view = walletViewRepository.findByCreditAccountId(cmd.creditAccountId())
                .orElseThrow(() -> new WalletViewNotFoundException(cmd.creditAccountId().toString()));

        // Débito atómico y condicional — 0 filas = saldo insuficiente (evita lost-update, 422).
        int rows = walletViewRepository.debitWalletBalanceIfEnough(cmd.creditAccountId(), cmd.amount());
        if (rows == 0) {
            throw new InsufficientWalletBalanceException(cmd.amount(), view.getWalletBalance());
        }

        WalletWithdrawal withdrawal = WalletWithdrawal.create(
                cmd.creditAccountId(), cmd.obligorPartyId(), cmd.method(), cmd.amount(), cmd.payeeAccount());
        withdrawalRepository.save(withdrawal);

        movementRepository.save(WalletMovement.withdrawal(cmd.creditAccountId(), cmd.obligorPartyId(),
                cmd.amount(), cmd.payeeAccount(), withdrawal.getWithdrawalId().toString()));

        // BK-12 · Aquí NO sale dinero. `WalletDispatchPort` era un despachador PARALELO: disparaba
        // un stub y marcaba el retiro enviado, cuando el camino correcto ya existía y funcionaba —
        // `wallet.withdrawal-completed` lo consume `disbursement`. Era un duplicado que se
        // adelantaba al evento, no una integración.
        //
        // El retiro queda pendiente hasta que quien paga confirme.
        log.info("WalletWithdrawal solicitado withdrawalId={} creditAccountId={} monto={}",
                withdrawal.getWithdrawalId(), cmd.creditAccountId(), cmd.amount());

        eventPublisher.publishWithdrawalCompleted(withdrawal);
        walletViewRepository.findByCreditAccountId(cmd.creditAccountId())
                .ifPresent(eventPublisher::publishWalletSnapshotUpdated);
        return withdrawal;
    }
}

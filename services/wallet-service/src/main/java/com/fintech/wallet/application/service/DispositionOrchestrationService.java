package com.fintech.wallet.application.service;

import com.fintech.wallet.application.RequestDispositionCommand;
import com.fintech.wallet.application.port.in.RequestDispositionUseCase;
import com.fintech.wallet.application.port.out.WalletEventPublisher;
import com.fintech.wallet.application.port.out.WalletViewRepository;
import com.fintech.wallet.domain.DispositionBlockedException;
import com.fintech.wallet.domain.InsufficientCreditException;
import com.fintech.wallet.domain.WalletViewNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class DispositionOrchestrationService implements RequestDispositionUseCase {

    private static final Logger log = LoggerFactory.getLogger(DispositionOrchestrationService.class);

    private final WalletViewRepository walletViewRepository;
    private final WalletEventPublisher eventPublisher;

    public DispositionOrchestrationService(WalletViewRepository walletViewRepository,
                                            WalletEventPublisher eventPublisher) {
        this.walletViewRepository = walletViewRepository;
        this.eventPublisher       = eventPublisher;
    }

    @Override
    public void request(RequestDispositionCommand cmd) {
        var view = walletViewRepository.findByCreditAccountId(cmd.creditAccountId())
                .orElseThrow(() -> new WalletViewNotFoundException(cmd.creditAccountId().toString()));

        // DO-05: SUSPENDED → block in Wallet before it reaches CreditProduct
        if (view.isSuspended()) {
            throw new DispositionBlockedException("account is SUSPENDED");
        }

        // DO-02: fail-fast availableCredit check
        if (view.getAvailableCredit() != null
                && cmd.amount().compareTo(view.getAvailableCredit()) > 0) {
            throw new InsufficientCreditException(cmd.amount(), view.getAvailableCredit());
        }

        // DO-03: DISTRIBUTOR_LINE requires beneficiaryPartyId
        if ("DISTRIBUTOR_LINE".equals(view.getProductType())
                && cmd.beneficiaryPartyId() == null) {
            throw new DispositionBlockedException("DISTRIBUTOR_LINE disposition requires beneficiaryPartyId");
        }

        // DO-04: el beneficiario no puede ser el propio acreditado.
        //
        // Si lo fuera no habría colocación: sería la distribuidora usando su línea para sí misma, y
        // eso es un crédito de uso propio —con su producto, su expediente y su riesgo—, no una
        // venta a un cliente final. Sin esta regla, alguien podría cobrarse comisión por prestarse
        // a sí mismo. Se corta aquí, en el canal, para que el rechazo llegue como respuesta a quien
        // lo pidió y no como un evento asíncrono que nadie mira.
        if (cmd.beneficiaryPartyId() != null
                && cmd.beneficiaryPartyId().equals(cmd.obligorPartyId())) {
            throw new DispositionBlockedException(
                    "El beneficiario no puede ser el mismo acreditado: para uso propio va un crédito SELF_USE");
        }

        log.info("Emitting DispositionRequested creditAccountId={} amount={} type={}",
                cmd.creditAccountId(), cmd.amount(), cmd.dispositionType());

        eventPublisher.publishDispositionRequested(
                cmd.creditAccountId(), cmd.obligorPartyId(),
                cmd.amount(), cmd.dispositionType(),
                cmd.beneficiaryPartyId(), cmd.payeeAccount(), cmd.termPeriods());
    }
}

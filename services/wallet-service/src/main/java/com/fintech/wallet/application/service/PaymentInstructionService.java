package com.fintech.wallet.application.service;

import com.fintech.wallet.application.CreatePaymentInstructionCommand;
import com.fintech.wallet.application.WalletProperties;
import com.fintech.wallet.application.port.in.CreatePaymentInstructionUseCase;
import com.fintech.wallet.application.port.out.PaymentInstructionRepository;
import com.fintech.wallet.application.port.out.WalletEventPublisher;
import com.fintech.wallet.application.port.out.WalletMovementRepository;
import com.fintech.wallet.application.port.out.WalletViewRepository;
import com.fintech.wallet.domain.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.UUID;

@Service
@Transactional
public class PaymentInstructionService implements CreatePaymentInstructionUseCase {

    private static final Logger log = LoggerFactory.getLogger(PaymentInstructionService.class);

    private final WalletViewRepository walletViewRepository;
    private final PaymentInstructionRepository instructionRepository;
    private final WalletMovementRepository movementRepository;
    private final WalletEventPublisher eventPublisher;
    private final WalletProperties properties;

    public PaymentInstructionService(WalletViewRepository walletViewRepository,
                                      PaymentInstructionRepository instructionRepository,
                                      WalletMovementRepository movementRepository,
                                      WalletEventPublisher eventPublisher,
                                      WalletProperties properties) {
        this.walletViewRepository  = walletViewRepository;
        this.instructionRepository = instructionRepository;
        this.movementRepository    = movementRepository;
        this.eventPublisher        = eventPublisher;
        this.properties            = properties;
    }

    @Override
    public PaymentInstruction create(CreatePaymentInstructionCommand cmd) {
        WalletView view = walletViewRepository.findByCreditAccountId(cmd.creditAccountId())
                .orElseThrow(() -> new WalletViewNotFoundException(cmd.creditAccountId().toString()));

        // PI-03: DOMICILIACION requires registered CLABE
        if (cmd.paymentMethod() == PaymentMethod.DOMICILIACION
                && (view.getRegisteredClabe() == null || view.getRegisteredClabe().isBlank())) {
            throw new DispositionBlockedException("DOMICILIACION requires a registered CLABE");
        }

        // PI-06: one PENDING per (creditAccountId, paymentMethod)
        if (instructionRepository.existsPendingByAccountAndMethod(
                cmd.creditAccountId(), cmd.paymentMethod().name())) {
            throw new DuplicatePendingInstructionException(
                    cmd.creditAccountId().toString(), cmd.paymentMethod().name());
        }

        BigDecimal amount = resolveAmount(cmd, view);
        Instant expiresAt = computeExpiry(cmd.paymentMethod());
        String paymentRef = null;
        // TODO: clarify CoDi token generation — integrate CoDiAdapter for CODI method

        PaymentInstruction instruction = PaymentInstruction.create(
                cmd.creditAccountId(), cmd.obligorPartyId(),
                cmd.paymentMethod(), amount, cmd.paymentType(),
                cmd.scheduledAt(), expiresAt, paymentRef);

        instructionRepository.save(instruction);
        movementRepository.save(WalletMovement.payment(
                cmd.creditAccountId(), cmd.obligorPartyId(), amount,
                instruction.getInstructionId().toString()));
        log.info("PaymentInstruction created instructionId={} method={} amount={} type={}",
                instruction.getInstructionId(), instruction.getPaymentMethod(),
                instruction.getAmount(), instruction.getPaymentType());

        eventPublisher.publishPaymentInstructionCreated(instruction);
        return instruction;
    }

    private BigDecimal resolveAmount(CreatePaymentInstructionCommand cmd, WalletView view) {
        return switch (cmd.paymentType()) {
            case MINIMUM -> {
                if (view.getMinimumPayment() == null) {
                    throw new IllegalStateException("No minimum payment available for creditAccountId="
                            + cmd.creditAccountId());
                }
                yield view.getMinimumPayment();
            }
            case SETTLEMENT -> view.getTotalDebt(); // PI-05: snapshot at creation
            default -> cmd.amount();
        };
    }

    private Instant computeExpiry(PaymentMethod method) {
        return switch (method) {
            case CODI -> Instant.now().plusSeconds((long) properties.getCodiExpiryMinutes() * 60);
            case SPEI -> {
                // IP-04: expires end of current SPEI window
                ZonedDateTime now = ZonedDateTime.now(ZoneId.of(properties.getSpeiTimeZone()));
                ZonedDateTime endOfWindow = now.withHour(properties.getSpeiEndHour())
                        .withMinute(59).withSecond(59).withNano(0);
                if (now.getHour() >= properties.getSpeiEndHour()) {
                    endOfWindow = endOfWindow.plusDays(1)
                            .withHour(properties.getSpeiEndHour());
                }
                yield endOfWindow.toInstant();
            }
            default -> Instant.now().plusSeconds(86400); // 24h default
        };
    }
}

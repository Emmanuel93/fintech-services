package com.fintech.charges.application.service;

import com.fintech.charges.application.port.out.AccrualScheduleRepository;
import com.fintech.charges.application.port.out.ChargeEventPublisher;
import com.fintech.charges.application.port.out.ChargeRecordRepository;
import com.fintech.charges.domain.ChargeRecord;
import com.fintech.charges.domain.ChargeStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * An installment credit was cancelled because its only disbursement could not happen (D63): it never lent anything,
 * so nothing it was charged for stands. Every charge still applied (the opening fee and its IVA, any interest or
 * moratorium accrued meanwhile) is reversed, and the portfolio hears each reversal as it does for any other. The
 * accrual schedule closes: a cancelled credit accrues nothing more.
 *
 * <p>Idempotent: a redelivery finds the charges reversed already and does nothing.
 */
@Service
@Transactional
public class CancellationCompensationService {

    private static final Logger log = LoggerFactory.getLogger(CancellationCompensationService.class);

    private final ChargeRecordRepository charges;
    private final AccrualScheduleRepository schedules;
    private final ChargeEventPublisher publisher;

    public CancellationCompensationService(ChargeRecordRepository charges, AccrualScheduleRepository schedules,
                                           ChargeEventPublisher publisher) {
        this.charges = charges;
        this.schedules = schedules;
        this.publisher = publisher;
    }

    public int onCreditAccountCancelled(UUID creditAccountId, String reason) {
        String why = "Crédito cancelado: " + (reason == null ? "desembolso fallido" : reason);
        int reversed = 0;
        for (ChargeRecord charge : charges.findAllByCreditAccountIdOrderByAccrualDateDesc(creditAccountId)) {
            if (!ChargeStatus.APPLIED.name().equals(charge.getStatus())) {
                continue;   // reversed or waived already: a redelivery, or an operator got there first
            }
            charge.reverse(why);
            charges.save(charge);
            publisher.publishChargeReversed(charge.getChargeId().toString(), charge.getCreditAccountId(),
                    charge.getChargeType(), charge.getTotalAmount(), false);
            reversed++;
        }
        schedules.findByCreditAccountId(creditAccountId).ifPresent(schedule -> {
            schedule.close();
            schedules.save(schedule);
        });
        log.info("Crédito cancelado compensado en cargos creditAccountId={} cargos reversados={} motivo={}",
                creditAccountId, reversed, reason);
        return reversed;
    }
}

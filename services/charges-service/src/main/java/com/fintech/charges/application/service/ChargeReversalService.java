package com.fintech.charges.application.service;

import com.fintech.charges.application.port.out.ChargeEventPublisher;
import com.fintech.charges.application.port.out.ChargeRecordRepository;
import com.fintech.charges.domain.ChargeRecord;
import com.fintech.charges.domain.ChargeRecordNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Transactional
public class ChargeReversalService {

    private static final Logger log = LoggerFactory.getLogger(ChargeReversalService.class);

    private final ChargeRecordRepository chargeRecordRepository;
    private final ChargeEventPublisher eventPublisher;

    public ChargeReversalService(ChargeRecordRepository chargeRecordRepository,
                                  ChargeEventPublisher eventPublisher) {
        this.chargeRecordRepository = chargeRecordRepository;
        this.eventPublisher        = eventPublisher;
    }

    public ChargeRecord reverse(UUID chargeId, String reason) {
        ChargeRecord charge = chargeRecordRepository.findById(chargeId)
                .orElseThrow(() -> new ChargeRecordNotFoundException(chargeId.toString()));

        charge.reverse(reason);
        chargeRecordRepository.save(charge);

        // Reverse linked IVA automatically (CR-06)
        chargeRecordRepository.findByLinkedChargeId(chargeId).ifPresent(iva -> {
            iva.reverse(reason + " (linked IVA)");
            chargeRecordRepository.save(iva);
        });

        eventPublisher.publishChargeReversed(charge.getChargeId().toString(),
                charge.getCreditAccountId(), charge.getChargeType(),
                charge.getTotalAmount(), false);

        log.info("ChargeReversed chargeId={} creditAccountId={} type={} reason={}",
                chargeId, charge.getCreditAccountId(), charge.getChargeType(), reason);
        return charge;
    }

    public ChargeRecord waive(UUID chargeId, String waivedBy) {
        ChargeRecord charge = chargeRecordRepository.findById(chargeId)
                .orElseThrow(() -> new ChargeRecordNotFoundException(chargeId.toString()));

        charge.waive(waivedBy);
        chargeRecordRepository.save(charge);

        // Waive linked IVA automatically (CR-06)
        chargeRecordRepository.findByLinkedChargeId(chargeId).ifPresent(iva -> {
            iva.waive(waivedBy + " (linked IVA)");
            chargeRecordRepository.save(iva);
        });

        // waived=true distinguishes from reversal for T4 accounting (loss vs. income cancellation)
        eventPublisher.publishChargeReversed(charge.getChargeId().toString(),
                charge.getCreditAccountId(), charge.getChargeType(),
                charge.getTotalAmount(), true);

        log.info("ChargeWaived chargeId={} creditAccountId={} type={} waivedBy={}",
                chargeId, charge.getCreditAccountId(), charge.getChargeType(), waivedBy);
        return charge;
    }

    @Transactional(readOnly = true)
    public ChargeRecord findById(UUID chargeId) {
        return chargeRecordRepository.findById(chargeId)
                .orElseThrow(() -> new ChargeRecordNotFoundException(chargeId.toString()));
    }
}

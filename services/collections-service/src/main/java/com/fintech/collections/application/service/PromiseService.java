package com.fintech.collections.application.service;

import com.fintech.collections.application.CreatePaymentPromiseCommand;
import com.fintech.collections.application.port.in.CreatePaymentPromiseUseCase;
import com.fintech.collections.application.port.out.CollectionCaseRepository;
import com.fintech.collections.application.port.out.CollectionsEventPublisher;
import com.fintech.collections.application.port.out.PaymentPromiseRepository;
import com.fintech.collections.application.port.out.WriteOffRecordRepository;
import com.fintech.collections.domain.CollectionCaseNotFoundException;
import com.fintech.collections.domain.InvalidCaseStateException;
import com.fintech.collections.domain.PaymentPromise;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** PP-*: payment promise tracking within an open case. Also RR-01: post-write-off recovery detection. */
@Service
@Transactional
public class PromiseService implements CreatePaymentPromiseUseCase {

    private static final Logger log = LoggerFactory.getLogger(PromiseService.class);

    private final CollectionCaseRepository caseRepository;
    private final PaymentPromiseRepository promiseRepository;
    private final WriteOffRecordRepository writeOffRepository;
    private final CollectionsEventPublisher eventPublisher;
    private final CommunicationHoldService holdService;
    private final DunningService dunningService;

    public PromiseService(CollectionCaseRepository caseRepository,
                           PaymentPromiseRepository promiseRepository,
                           WriteOffRecordRepository writeOffRepository,
                           CollectionsEventPublisher eventPublisher,
                           CommunicationHoldService holdService,
                           DunningService dunningService) {
        this.caseRepository     = caseRepository;
        this.promiseRepository  = promiseRepository;
        this.writeOffRepository = writeOffRepository;
        this.eventPublisher     = eventPublisher;
        this.holdService        = holdService;
        this.dunningService     = dunningService;
    }

    @Override
    public PaymentPromise create(CreatePaymentPromiseCommand cmd) {
        var collectionCase = caseRepository.findById(cmd.caseId())
                .orElseThrow(() -> new CollectionCaseNotFoundException(cmd.caseId().toString()));
        if (collectionCase.getStatus().isTerminal()) {
            throw new InvalidCaseStateException("Case " + cmd.caseId() + " is " + collectionCase.getStatus()
                    + " — cannot record new promises");
        }
        // PP-01: only one ACTIVE promise per case
        promiseRepository.findActiveByCaseId(cmd.caseId()).ifPresent(p -> {
            throw new InvalidCaseStateException("Case " + cmd.caseId() + " already has an ACTIVE promise: " + p.getPromiseId());
        });

        PaymentPromise promise = PaymentPromise.create(cmd.caseId(), cmd.amount(), cmd.promisedDate(), cmd.recordedBy());
        promiseRepository.save(promise);
        log.info("PaymentPromise created promiseId={} caseId={} amount={} date={}",
                promise.getPromiseId(), cmd.caseId(), cmd.amount(), cmd.promisedDate());
        eventPublisher.publishPaymentPromiseMade(promise);
        // Desde este momento la cadencia calla: seguir insistiéndole a quien acaba de comprometerse
        // a pagar el viernes destruye el compromiso que se acaba de conseguir.
        holdService.onPromiseMade(promise);
        return promise;
    }

/**
     * PP-05: a payment covering the promised amount, while active, keeps the promise (CM-04 logs
     * partial payments that don't). RR-01: a payment with no active case, against an already
     * written-off account, is a post-write-off recovery — tracked here, never reopens the case.
     */
    public void onPaymentApplied(UUID creditAccountId, UUID paymentId, BigDecimal amount, String paymentMethod) {
        var activeCase = caseRepository.findActiveByCreditAccountId(creditAccountId);
        if (activeCase.isPresent()) {
            UUID caseId = activeCase.get().getCaseId();
            promiseRepository.findActiveByCaseId(caseId).ifPresent(promise -> {
                if (amount.compareTo(promise.getAmount()) >= 0) {
                    promise.markKept(paymentId);
                    promiseRepository.save(promise);
                    log.info("PaymentPromise kept promiseId={} caseId={}", promise.getPromiseId(), caseId);
                    // Cumplir tiene consecuencias visibles: se agradece y se concede silencio. Si al
                    // día siguiente llegara el mismo recordatorio que antes de pagar, la lección
                    // sería que cumplir no cambia nada.
                    dunningService.thank(activeCase.get(), amount);
                    holdService.onPromiseKept(promise);
                } else {
                    // CM-04: abono parcial. No cumple la promesa —el importe no se cubrió— pero sí
                    // extiende la paciencia: tratar igual a quien abonó la mitad que a quien ignoró
                    // todo es la forma de conseguir que la próxima vez no abone nada.
                    holdService.onPartialPayment(caseId);
                    log.info("Partial payment recorded against active case caseId={} amount={} (promise amount={})",
                            caseId, amount, promise.getAmount());
                }
            });
            return;
        }

        writeOffRepository.findByCreditAccountId(creditAccountId).ifPresent(writeOff -> {
            log.info("Recovery payment detected post write-off writeOffId={} creditAccountId={} amount={}",
                    writeOff.getWriteOffId(), creditAccountId, amount);
            eventPublisher.publishRecoveryPaymentApplied(writeOff.getWriteOffId(), creditAccountId, amount, paymentMethod);
        });
    }

    /** PP-04: broken by the nightly job when promisedDate has already passed and the promise is still ACTIVE. */
    public void checkBrokenPromises(LocalDate today) {
        List<PaymentPromise> overdue = promiseRepository.findActiveWithPromisedDateBefore(today);
        for (PaymentPromise promise : overdue) {
            promise.markBroken();
            promiseRepository.save(promise);
            log.info("PaymentPromise broken promiseId={} caseId={}", promise.getPromiseId(), promise.getCaseId());
            eventPublisher.publishPaymentPromiseBroken(promise);
            // El freno se levanta explícitamente en vez de dejarlo expirar: la cadencia tiene que
            // poder distinguir «todavía no le toca» de «se le acabó el plazo y no cumplió».
            holdService.onPromiseBroken(promise);
        }
    }
}

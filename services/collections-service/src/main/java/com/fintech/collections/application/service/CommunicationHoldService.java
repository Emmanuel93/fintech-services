package com.fintech.collections.application.service;

import com.fintech.collections.application.CollectionsProperties;
import com.fintech.collections.application.port.out.CommunicationHoldRepository;
import com.fintech.collections.domain.CommunicationHold;
import com.fintech.collections.domain.HoldReason;
import com.fintech.collections.domain.PaymentPromise;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/**
 * Decide cuándo la cobranza automática se calla, y deja constancia de por qué.
 *
 * <p>Todo lo que abre un freno pasa por aquí en vez de que cada servicio escriba su propia regla:
 * el silencio es una promesa que se le hace al cliente, y repartirla entre cinco sitios garantiza
 * que alguno la rompa.
 */
@Service
@Transactional
public class CommunicationHoldService {

    private static final Logger log = LoggerFactory.getLogger(CommunicationHoldService.class);

    private final CommunicationHoldRepository holdRepository;
    private final CollectionsProperties properties;

    public CommunicationHoldService(CommunicationHoldRepository holdRepository,
                                     CollectionsProperties properties) {
        this.holdRepository = holdRepository;
        this.properties     = properties;
    }

    /**
     * Una promesa viva calla hasta su fecha más la gracia.
     *
     * <p>La gracia existe porque un pago hecho el día prometido puede tardar en aplicarse: sin ella
     * el job de las 08:00 rompería promesas que sí se cumplieron.
     */
    public void onPromiseMade(PaymentPromise promise) {
        Instant hasta = endOfDay(promise.getPromisedDate().plusDays(properties.getPromiseGraceDays()));
        abrirOExtender(promise.getCaseId(), HoldReason.ACTIVE_PROMISE, hasta, promise.getPromiseId());
    }

    /**
     * Cumplir concede silencio, incluso si el caso sigue con saldo.
     *
     * <p>Es el refuerzo del comportamiento que se quiere: quien pagó lo que prometió no puede
     * recibir al día siguiente el mismo recordatorio que recibía antes de pagar. Si la mora se
     * limpió del todo, el caso cierra por su cuenta y este freno da igual; si quedó saldo, aquí es
     * donde se le da aire antes de volver a insistir.
     */
    public void onPromiseKept(PaymentPromise promise) {
        Instant hasta = Instant.now().plus(Duration.ofDays(properties.getPromiseKeptQuietDays()));
        abrirOExtender(promise.getCaseId(), HoldReason.PROMISE_KEPT, hasta, promise.getPromiseId());
        log.info("Silencio por promesa cumplida caseId={} hasta={}", promise.getCaseId(), hasta);
    }

    /**
     * Un abono parcial no cumple la promesa pero sí extiende la paciencia.
     *
     * <p>Tratar igual a quien abonó la mitad que a quien ignoró todo es la manera de conseguir que
     * la próxima vez no abone nada.
     */
    public void onPartialPayment(UUID caseId) {
        Instant hasta = Instant.now().plus(Duration.ofDays(properties.getPartialPaymentQuietDays()));
        abrirOExtender(caseId, HoldReason.ACTIVE_PROMISE, hasta, null);
    }

    /**
     * Una promesa rota levanta el freno.
     *
     * <p>No lo deja expirar solo: la cadencia tiene que poder distinguir «todavía no le toca» de
     * «se le acabó el plazo y no cumplió», y eso se lee del freno liberado, no de su fecha.
     */
    public void onPromiseBroken(PaymentPromise promise) {
        holdRepository.findActive(promise.getCaseId(), HoldReason.ACTIVE_PROMISE, Instant.now())
                .ifPresent(h -> {
                    h.release(null, "Promesa incumplida");
                    holdRepository.save(h);
                    log.info("Freno levantado por promesa rota caseId={}", promise.getCaseId());
                });
    }

    /** Negociar y presionar a la vez no funciona: el convenio calla hasta que se resuelva. */
    public void onAgreementInFlight(UUID caseId, UUID agreementId, int diasDeVigencia) {
        Instant hasta = Instant.now().plus(Duration.ofDays(diasDeVigencia));
        abrirOExtender(caseId, HoldReason.AGREEMENT_IN_FLIGHT, hasta, agreementId);
    }

    public void onAgreementResolved(UUID caseId) {
        holdRepository.findActive(caseId, HoldReason.AGREEMENT_IN_FLIGHT, Instant.now())
                .ifPresent(h -> {
                    h.release(null, "Convenio resuelto");
                    holdRepository.save(h);
                });
    }

    /** Lo pone un agente. Exige motivo: un silencio sin razón es un caso perdido a propósito. */
    public CommunicationHold hold(UUID caseId, Instant heldUntil, String by, String motivo) {
        CommunicationHold h = CommunicationHold.open(caseId, HoldReason.MANUAL, heldUntil, null);
        h = holdRepository.save(h);
        log.info("Freno manual caseId={} hasta={} por={} motivo={}", caseId, heldUntil, by, motivo);
        return h;
    }

    public void release(UUID caseId, HoldReason reason, String by, String note) {
        holdRepository.findActive(caseId, reason, Instant.now()).ifPresent(h -> {
            h.release(by, note);
            holdRepository.save(h);
        });
    }

    @Transactional(readOnly = true)
    public List<CommunicationHold> activeHolds(UUID caseId) {
        return holdRepository.findAllActive(caseId, Instant.now());
    }

    @Transactional(readOnly = true)
    public List<CommunicationHold> history(UUID caseId) {
        return holdRepository.findByCaseIdOrderByCreatedAtDesc(caseId);
    }

    @Transactional(readOnly = true)
    public boolean isSilenced(UUID caseId) {
        return !holdRepository.findAllActive(caseId, Instant.now()).isEmpty();
    }

    private void abrirOExtender(UUID caseId, HoldReason reason, Instant hasta, UUID sourceId) {
        holdRepository.findActive(caseId, reason, Instant.now())
                .ifPresentOrElse(
                        h -> { h.extendTo(hasta); holdRepository.save(h); },
                        () -> holdRepository.save(CommunicationHold.open(caseId, reason, hasta, sourceId)));
    }

    /** Fin del día local: una promesa «para el 20» vale todo el 20, no hasta las 00:00 del 20. */
    private static Instant endOfDay(LocalDate day) {
        return day.atTime(23, 59, 59).atZone(ZoneId.systemDefault()).toInstant();
    }
}

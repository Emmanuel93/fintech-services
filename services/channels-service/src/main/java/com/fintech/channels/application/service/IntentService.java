package com.fintech.channels.application.service;

import com.fintech.channels.application.port.out.*;
import com.fintech.channels.domain.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

@Service
@Transactional
public class IntentService {

    private static final Logger log = LoggerFactory.getLogger(IntentService.class);

    private final SessionRepository sessionRepository;
    private final ChannelRepository channelRepository;
    private final CustomerIntentRepository intentRepository;
    private final ChannelsEventPublisher eventPublisher;
    private final PartyStatusChecker partyStatusChecker;

    public IntentService(SessionRepository sessionRepository,
                         ChannelRepository channelRepository,
                         CustomerIntentRepository intentRepository,
                         ChannelsEventPublisher eventPublisher,
                         PartyStatusChecker partyStatusChecker) {
        this.sessionRepository  = sessionRepository;
        this.channelRepository  = channelRepository;
        this.intentRepository   = intentRepository;
        this.eventPublisher     = eventPublisher;
        this.partyStatusChecker = partyStatusChecker;
    }

    public CustomerIntent captureIntent(UUID sessionId, IntentType intentType,
                                        String productTypeHint, BigDecimal requestedAmount,
                                        String promoterCode) {
        Session session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new SessionNotFoundException(sessionId.toString()));

        // S-01: expired/closed sessions don't accept intents
        if (!session.isAcceptingIntents()) {
            throw new IllegalStateException("Session " + sessionId + " is not accepting intents (S-01)");
        }

        Channel channel = channelRepository.findById(session.getChannelId())
                .orElseThrow(() -> new ChannelNotFoundException(session.getChannelId().toString()));

        // IR-03: intentType must be in channel.allowedIntents
        if (!channel.allowsIntent(intentType)) {
            throw new IllegalArgumentException(
                    "IntentType " + intentType + " not allowed in channel " + channel.getChannelType() + " (IR-03)");
        }

        CustomerIntent intent = CustomerIntent.capture(sessionId, intentType,
                productTypeHint, requestedAmount, promoterCode);
        intentRepository.save(intent);
        eventPublisher.publishIntentCaptured(intent.getIntentId(), sessionId, intentType.name(),
                productTypeHint, requestedAmount);
        return intent;
    }

    public CustomerIntent routeIntent(UUID sessionId, UUID intentId, String requestingUserId) {
        Session session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new SessionNotFoundException(sessionId.toString()));

        CustomerIntent intent = intentRepository.findByIdAndSessionId(intentId, sessionId)
                .orElseThrow(() -> new CustomerIntentNotFoundException(intentId.toString()));

        if (!intent.isCaptured()) {
            throw new IllegalStateException("Intent " + intentId + " is not in CAPTURED state");
        }

        // IR-02: BLACKLISTED party → IntentAbandoned — never reaches Origination
        //
        // **Falla CERRADO.** Antes, cualquier excepción al consultar a party se registraba con un
        // WARN y el intent seguía su camino sin comprobar. Para un control regulatorio esa
        // polaridad está invertida: que la comprobación no esté disponible tiene que DETENER, no
        // dejar pasar. Con el screening de listas como condición para colocar, fallar abierto
        // significaría dispersar dinero a alguien sin haber comprobado que no está en una lista,
        // porque un servicio estaba caído — y sin que nada lo indicara después.
        //
        // No se abandona el intent: no se sabe si la persona está o no en lista, y abandonarlo
        // diría que sí. Se rechaza el enrutamiento, que es reintentable en cuanto party conteste.
        if (session.getPartyId() != null) {
            PartyStatusChecker.PartyStatus partyStatus;
            try {
                partyStatus = partyStatusChecker.check(session.getPartyId(), requestingUserId);
            } catch (Exception ex) {
                log.error("No se pudo comprobar el estatus de party partyId={} — el intent NO avanza: {}",
                        session.getPartyId(), ex.toString());
                throw new PartyStatusUnavailableException(session.getPartyId(), ex);
            }
            if (partyStatus.isBlacklisted()) {
                intent.abandon("PARTY_BLACKLISTED");
                intentRepository.save(intent);
                eventPublisher.publishIntentAbandoned(intent.getIntentId(), "PARTY_BLACKLISTED");
                return intent;
            }
        }

        IntentType intentType = IntentType.valueOf(intent.getIntentType());
        DomainTarget target = DomainTarget.forIntent(intentType);
        intent.route(target);
        intentRepository.save(intent);
        eventPublisher.publishIntentRouted(intent.getIntentId(), target.name());

        // ApplicationStarted is emitted only for origination-bound intents
        if (target == DomainTarget.CREDIT_ORIGINATION && session.getPartyId() != null) {
            eventPublisher.publishApplicationStarted(
                    intent.getIntentId(), session.getPartyId(), session.getChannelId(),
                    intent.getProductTypeHint(), intent.getRequestedAmount(), intent.getPromoterCode());
        }

        return intent;
    }

    public CustomerIntent abandonIntent(UUID sessionId, UUID intentId, String reason) {
        Session session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new SessionNotFoundException(sessionId.toString()));

        CustomerIntent intent = intentRepository.findByIdAndSessionId(intentId, sessionId)
                .orElseThrow(() -> new CustomerIntentNotFoundException(intentId.toString()));

        if (!intent.isCaptured()) {
            throw new IllegalStateException("Intent " + intentId + " is not in CAPTURED state");
        }

        intent.abandon(reason != null ? reason : "MANUAL_ABANDON");
        intentRepository.save(intent);
        eventPublisher.publishIntentAbandoned(intent.getIntentId(), intent.getAbandonReason());
        return intent;
    }
}

package com.fintech.origination.infrastructure.adapter.in.messaging;

import com.fintech.origination.application.StartCreditApplicationCommand;
import com.fintech.origination.application.port.in.StartCreditApplicationUseCase;
import com.fintech.origination.application.port.out.PartyReader;
import com.fintech.origination.domain.CooldownActiveException;
import com.fintech.origination.domain.DuplicateActiveApplicationException;
import com.fintech.origination.domain.ProductType;
import com.fintech.origination.domain.ProspectType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code channels.application-started} and creates a {@link com.fintech.origination.domain.CreditApplication}.
 *
 * <p>Resolution chain:
 * <ol>
 *   <li>Resolve {@code partyId} → {@code (prospectId, prospectType)} via party-service ACL</li>
 *   <li>Map {@code productTypeHint} → {@link ProductType}</li>
 *   <li>Delegate to {@link StartCreditApplicationUseCase} — idempotent: duplicate is logged and skipped</li>
 * </ol>
 *
 * <p>Fail-closed on ACL errors: if party-service is unavailable the event is NOT processed
 * (Spring Kafka will retry based on consumer configuration). This is intentional — starting
 * a credit application without knowing the prospectId is impossible.
 */
@Component
public class ApplicationStartedEventListener {

    private static final Logger log = LoggerFactory.getLogger(ApplicationStartedEventListener.class);

    private final StartCreditApplicationUseCase startCreditApplicationUseCase;
    private final PartyReader partyReader;

    public ApplicationStartedEventListener(StartCreditApplicationUseCase startCreditApplicationUseCase,
                                           PartyReader partyReader) {
        this.startCreditApplicationUseCase = startCreditApplicationUseCase;
        this.partyReader = partyReader;
    }

    @KafkaListener(
            topics = "channels.application-started",
            groupId = "origination-service",
            containerFactory = "applicationStartedListenerContainerFactory")
    public void onApplicationStarted(@Payload ApplicationStartedPayload payload) {
        log.info("Received ApplicationStarted intentId={} partyId={} productTypeHint={}",
                payload.intentId(), payload.partyId(), payload.productTypeHint());

        // 1. Resolve partyId → prospectId (fail-closed: without this we cannot proceed)
        PartyReader.PartyData party = partyReader.findByPartyId(payload.partyId()).orElse(null);
        if (party == null) {
            log.error("Cannot start credit application — party-service returned no data for partyId={}. " +
                      "Event intentId={} will not be retried automatically; check party-service health.",
                      payload.partyId(), payload.intentId());
            return;
        }

        // 2. Map productTypeHint → ProductType
        ProductType productType = resolveProductType(payload.productTypeHint(), payload.intentId());
        if (productType == null) {
            return;
        }

        // 3. Map partyType → ProspectType (same values, direct conversion)
        ProspectType prospectType;
        try {
            prospectType = ProspectType.valueOf(party.partyType());
        } catch (IllegalArgumentException | NullPointerException e) {
            log.warn("Unknown partyType '{}' for partyId={} intentId={} — skipping",
                    party.partyType(), payload.partyId(), payload.intentId());
            return;
        }

        // 4. Start credit application (OA-03 duplicate and UW-06 cooldown are guarded inside)
        try {
            var result = startCreditApplicationUseCase.start(new StartCreditApplicationCommand(
                    party.prospectId(),
                    productType,
                    payload.requestedAmount(),
                    null,                          // term: not provided at channel intent stage
                    payload.intentId().toString(), // correlationId ties back to the channel intent
                    payload.promoterCode()
            ));
            log.info("CreditApplication created via channel intent intentId={} prospectId={} result={}",
                    payload.intentId(), party.prospectId(), result.getClass().getSimpleName());

        } catch (DuplicateActiveApplicationException e) {
            log.info("Idempotent skip — active application already exists for prospectId={} productType={} intentId={}",
                    party.prospectId(), productType, payload.intentId());
        } catch (CooldownActiveException e) {
            log.warn("Cooldown active for prospectId={} productType={} intentId={} — {}",
                    party.prospectId(), productType, payload.intentId(), e.getMessage());
        }
    }

    private ProductType resolveProductType(String hint, Object intentId) {
        if (hint == null || hint.isBlank()) {
            log.warn("channels.application-started intentId={} has no productTypeHint — cannot determine product type, skipping",
                    intentId);
            return null;
        }
        try {
            return ProductType.valueOf(hint.toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("Unknown productTypeHint '{}' in intentId={} — skipping", hint, intentId);
            return null;
        }
    }
}

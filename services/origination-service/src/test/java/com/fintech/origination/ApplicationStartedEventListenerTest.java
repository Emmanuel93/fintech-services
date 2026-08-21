package com.fintech.origination;

import com.fintech.origination.application.StartCreditApplicationCommand;
import com.fintech.origination.application.StartCreditApplicationResult;
import com.fintech.origination.application.port.in.StartCreditApplicationUseCase;
import com.fintech.origination.application.port.out.PartyReader;
import com.fintech.origination.domain.CooldownActiveException;
import com.fintech.origination.domain.DuplicateActiveApplicationException;
import com.fintech.origination.domain.ApplicationStatus;
import com.fintech.origination.domain.ProductType;
import com.fintech.origination.infrastructure.adapter.in.messaging.ApplicationStartedEventListener;
import com.fintech.origination.infrastructure.adapter.in.messaging.ApplicationStartedPayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class ApplicationStartedEventListenerTest {

    @Mock StartCreditApplicationUseCase startUseCase;
    @Mock PartyReader partyReader;

    ApplicationStartedEventListener listener;

    private static final UUID INTENT_ID  = UUID.randomUUID();
    private static final UUID PARTY_ID   = UUID.randomUUID();
    private static final UUID CHANNEL_ID = UUID.randomUUID();
    private static final UUID PROSPECT_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        listener = new ApplicationStartedEventListener(startUseCase, partyReader);
    }

    @Test
    void onApplicationStarted_happyPath_startsCreditApplication() {
        given(partyReader.findByPartyId(PARTY_ID))
                .willReturn(Optional.of(new PartyReader.PartyData(PROSPECT_ID, "INDIVIDUAL")));
        given(startUseCase.start(any())).willReturn(
                new StartCreditApplicationResult.ApplicationStarted(
                        UUID.randomUUID(), PROSPECT_ID, ProductType.PERSONAL_LOAN, ApplicationStatus.PENDING_SCORING, Instant.now()));

        listener.onApplicationStarted(payload("PERSONAL_LOAN", new BigDecimal("30000")));

        ArgumentCaptor<StartCreditApplicationCommand> cap = ArgumentCaptor.forClass(StartCreditApplicationCommand.class);
        then(startUseCase).should().start(cap.capture());

        StartCreditApplicationCommand cmd = cap.getValue();
        assertThat(cmd.prospectId()).isEqualTo(PROSPECT_ID);
        assertThat(cmd.productType()).isEqualTo(ProductType.PERSONAL_LOAN);
        assertThat(cmd.requestedAmount()).isEqualByComparingTo("30000");
        assertThat(cmd.requestedTerm()).isNull();
        assertThat(cmd.correlationId()).isEqualTo(INTENT_ID.toString());
    }

    @Test
    void onApplicationStarted_partyNotFound_skipsWithoutException() {
        given(partyReader.findByPartyId(PARTY_ID)).willReturn(Optional.empty());

        listener.onApplicationStarted(payload("PERSONAL_LOAN", null));

        then(startUseCase).should(never()).start(any());
    }

    @Test
    void onApplicationStarted_nullProductTypeHint_skips() {
        given(partyReader.findByPartyId(PARTY_ID))
                .willReturn(Optional.of(new PartyReader.PartyData(PROSPECT_ID, "INDIVIDUAL")));

        listener.onApplicationStarted(payload(null, null));

        then(startUseCase).should(never()).start(any());
    }

    @Test
    void onApplicationStarted_blankProductTypeHint_skips() {
        given(partyReader.findByPartyId(PARTY_ID))
                .willReturn(Optional.of(new PartyReader.PartyData(PROSPECT_ID, "INDIVIDUAL")));

        listener.onApplicationStarted(payload("", null));

        then(startUseCase).should(never()).start(any());
    }

    @Test
    void onApplicationStarted_unknownProductTypeHint_skips() {
        given(partyReader.findByPartyId(PARTY_ID))
                .willReturn(Optional.of(new PartyReader.PartyData(PROSPECT_ID, "INDIVIDUAL")));

        listener.onApplicationStarted(payload("CRYPTO_LOAN", null));

        then(startUseCase).should(never()).start(any());
    }

    @Test
    void onApplicationStarted_duplicateApplication_idempotentSkip() {
        given(partyReader.findByPartyId(PARTY_ID))
                .willReturn(Optional.of(new PartyReader.PartyData(PROSPECT_ID, "INDIVIDUAL")));
        given(startUseCase.start(any()))
                .willThrow(new DuplicateActiveApplicationException(ProductType.PERSONAL_LOAN));

        // should not throw
        listener.onApplicationStarted(payload("PERSONAL_LOAN", new BigDecimal("30000")));
    }

    @Test
    void onApplicationStarted_cooldownActive_logsAndSkips() {
        given(partyReader.findByPartyId(PARTY_ID))
                .willReturn(Optional.of(new PartyReader.PartyData(PROSPECT_ID, "INDIVIDUAL")));
        given(startUseCase.start(any()))
                .willThrow(new CooldownActiveException("PERSONAL_LOAN", 90));

        // should not throw
        listener.onApplicationStarted(payload("PERSONAL_LOAN", new BigDecimal("30000")));
    }

    @Test
    void onApplicationStarted_businessPartyType_mapsCorrectly() {
        given(partyReader.findByPartyId(PARTY_ID))
                .willReturn(Optional.of(new PartyReader.PartyData(PROSPECT_ID, "BUSINESS")));
        given(startUseCase.start(any())).willReturn(
                new StartCreditApplicationResult.ApplicationStarted(
                        UUID.randomUUID(), PROSPECT_ID, ProductType.DISTRIBUTOR_LINE, ApplicationStatus.PENDING_SCORING, Instant.now()));

        listener.onApplicationStarted(payload("DISTRIBUTOR_LINE", new BigDecimal("500000")));

        then(startUseCase).should().start(any());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private ApplicationStartedPayload payload(String productTypeHint, BigDecimal amount) {
        return new ApplicationStartedPayload(INTENT_ID, PARTY_ID, CHANNEL_ID,
                productTypeHint, amount, null, Instant.now());
    }
}

package com.fintech.party.infrastructure.adapter;

import com.fintech.party.application.service.PartyService;
import com.fintech.party.domain.Party;
import com.fintech.party.domain.PartyType;
import com.fintech.party.infrastructure.adapter.in.messaging.ProspectCreatedEventListener;
import com.fintech.party.infrastructure.adapter.in.messaging.ProspectCreatedPayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class ScoringApprovedEventListenerTest {

    @Mock PartyService partyService;

    ProspectCreatedEventListener listener;

    @BeforeEach
    void setUp() {
        listener = new ProspectCreatedEventListener(partyService);
    }

    @Test
    void onProspectCreated_validPayload_delegatesToPartyService() {
        UUID prospectId = UUID.randomUUID();
        ProspectCreatedPayload payload = new ProspectCreatedPayload(
                prospectId,
                "INDIVIDUAL",
                "Ana",
                "Martínez",
                "Ruiz",
                "MARA950215MDFXXX02",
                "MARA950215XXX",
                LocalDate.of(1995, 2, 15),
                UUID.randomUUID().toString()
        );

        Party createdParty = Party.create(UUID.randomUUID(), prospectId,
                PartyType.INDIVIDUAL, "Ana", "Martínez", "Ruiz",
                "MARA950215MDFXXX02", "MARA950215XXX",
                LocalDate.of(1995, 2, 15));
        given(partyService.createFromProspect(any())).willReturn(createdParty);

        listener.onProspectCreated(payload);

        ArgumentCaptor<ProspectCreatedPayload> captor = ArgumentCaptor.forClass(ProspectCreatedPayload.class);
        then(partyService).should().createFromProspect(captor.capture());
        assertThat(captor.getValue().prospectId()).isEqualTo(prospectId);
        assertThat(captor.getValue().prospectType()).isEqualTo("INDIVIDUAL");
    }

    @Test
    void onProspectCreated_serviceThrows_exceptionPropagates() {
        given(partyService.createFromProspect(any()))
                .willThrow(new RuntimeException("DB unavailable"));

        ProspectCreatedPayload payload = new ProspectCreatedPayload(
                UUID.randomUUID(), "INDIVIDUAL",
                "Test", "User", null,
                "TSTU000101HDFXXX03", "TSTU000101XXX",
                LocalDate.of(2000, 1, 1),
                UUID.randomUUID().toString()
        );

        assertThatThrownBy(() -> listener.onProspectCreated(payload))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("DB unavailable");
    }
}

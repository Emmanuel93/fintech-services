package com.fintech.party.application.service;

import com.fintech.party.application.port.out.PartyEventPublisher;
import com.fintech.party.application.port.out.PartyRepository;
import com.fintech.party.application.port.out.PartyRoleRepository;
import com.fintech.party.domain.Party;
import com.fintech.party.domain.PartyNotFoundException;
import com.fintech.party.domain.PartyRole;
import com.fintech.party.domain.PartyRoleType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PartyRoleServiceTest {

    @Mock PartyRoleRepository roleRepository;
    @Mock PartyRepository partyRepository;
    @Mock PartyEventPublisher eventPublisher;

    PartyRoleService service;

    final UUID partyId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new PartyRoleService(roleRepository, partyRepository, eventPublisher);
    }

    @Test
    void grant_newRole_savesAndPublishesEvent() {
        given(partyRepository.findById(partyId)).willReturn(Optional.of(mock(Party.class)));
        given(roleRepository.findActiveByPartyIdAndRoleType(partyId, PartyRoleType.DISTRIBUTOR))
                .willReturn(Optional.empty());
        given(roleRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        PartyRole result = service.grant(partyId, PartyRoleType.DISTRIBUTOR, "admin-1");

        assertThat(result.getRoleType()).isEqualTo(PartyRoleType.DISTRIBUTOR);
        assertThat(result.isActive()).isTrue();
        verify(roleRepository).save(any(PartyRole.class));
        verify(eventPublisher).publishRoleGranted(partyId, "DISTRIBUTOR", "admin-1");
    }

    @Test
    void grant_existingActive_isIdempotent_noSaveNoEvent() {
        PartyRole existing = PartyRole.grant(partyId, PartyRoleType.DISTRIBUTOR, "someone");
        given(partyRepository.findById(partyId)).willReturn(Optional.of(mock(Party.class)));
        given(roleRepository.findActiveByPartyIdAndRoleType(partyId, PartyRoleType.DISTRIBUTOR))
                .willReturn(Optional.of(existing));

        PartyRole result = service.grant(partyId, PartyRoleType.DISTRIBUTOR, "admin-1");

        assertThat(result).isSameAs(existing);
        verify(roleRepository, never()).save(any());
        verify(eventPublisher, never()).publishRoleGranted(any(), any(), any());
    }

    @Test
    void grant_partyNotFound_throws() {
        given(partyRepository.findById(partyId)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.grant(partyId, PartyRoleType.DISTRIBUTOR, "admin-1"))
                .isInstanceOf(PartyNotFoundException.class);
        verify(roleRepository, never()).save(any());
    }

    @Test
    void revoke_active_closesAndPublishes_returnsTrue() {
        PartyRole active = PartyRole.grant(partyId, PartyRoleType.DISTRIBUTOR, "someone");
        given(roleRepository.findActiveByPartyIdAndRoleType(partyId, PartyRoleType.DISTRIBUTOR))
                .willReturn(Optional.of(active));

        boolean revoked = service.revoke(partyId, PartyRoleType.DISTRIBUTOR);

        assertThat(revoked).isTrue();
        assertThat(active.isActive()).isFalse();
        verify(roleRepository).save(active);
        verify(eventPublisher).publishRoleRevoked(partyId, "DISTRIBUTOR");
    }

    @Test
    void revoke_noActive_returnsFalse_noEvent() {
        given(roleRepository.findActiveByPartyIdAndRoleType(partyId, PartyRoleType.DISTRIBUTOR))
                .willReturn(Optional.empty());

        assertThat(service.revoke(partyId, PartyRoleType.DISTRIBUTOR)).isFalse();
        verify(eventPublisher, never()).publishRoleRevoked(any(), any());
    }
}

package com.fintech.identity;

import com.fintech.identity.application.*;
import com.fintech.identity.application.port.out.*;
import com.fintech.identity.application.service.ClientAuthService;
import com.fintech.identity.domain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class ClientAuthServiceTest {

    @Mock ClientRepository clientRepository;
    @Mock ClientIpRepository clientIpRepository;
    @Mock TokenRepository tokenRepository;
    @Mock TokenPort tokenPort;
    @Mock PasswordEncoder passwordEncoder;

    ClientAuthService service;
    AuthProperties properties;

    final String clientId = "scoring-service";
    final String rawSecret = "test-plain-secret";
    final String secretHash = "$2a$10$fakehashfortest";

    @BeforeEach
    void setUp() {
        properties = new AuthProperties();
        properties.setMaxFailedAttempts(5);
        properties.setLockoutDurationMinutes(30);
        properties.setAccessTokenExpiryMinutes(15);
        properties.setRefreshTokenExpiryDays(7);

        service = new ClientAuthService(clientRepository, clientIpRepository,
                tokenRepository, tokenPort, passwordEncoder, properties);
    }

    // ── authenticateClient ─────────────────────────────────────────────────

    @Test
    void authenticateClient_emptyWhitelist_allowsAnyIp() {
        Client client = activeClient();
        given(clientRepository.findByClientId(clientId)).willReturn(Optional.of(client));
        given(passwordEncoder.matches(rawSecret, secretHash)).willReturn(true);
        given(clientIpRepository.findByClientId(client.getId())).willReturn(List.of());
        given(tokenPort.generateAccessToken(eq(client.getId()), any(), any(), any())).willReturn("access.jwt");
        given(tokenPort.extractJti("access.jwt")).willReturn(UUID.randomUUID().toString());
        given(tokenRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        TokenPair pair = service.authenticateClient(clientId, rawSecret, "any.ip.here");

        assertThat(pair.accessToken()).isEqualTo("access.jwt");
        assertThat(pair.refreshToken()).isNotBlank().hasSize(64);
        assertThat(pair.expiresIn()).isEqualTo(15 * 60L);
    }

    @Test
    void authenticateClient_ipMatchesCidrRange_succeeds() {
        Client client = activeClient();
        ClientIpEntry entry = ClientIpEntry.create(client.getId(), "192.168.1.0/24", "dev");
        given(clientRepository.findByClientId(clientId)).willReturn(Optional.of(client));
        given(passwordEncoder.matches(rawSecret, secretHash)).willReturn(true);
        given(clientIpRepository.findByClientId(client.getId())).willReturn(List.of(entry));
        given(tokenPort.generateAccessToken(any(), any(), any(), any())).willReturn("access.jwt");
        given(tokenPort.extractJti(any())).willReturn(UUID.randomUUID().toString());
        given(tokenRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        assertThatCode(() -> service.authenticateClient(clientId, rawSecret, "192.168.1.100"))
                .doesNotThrowAnyException();
    }

    @Test
    void authenticateClient_clientNotFound_throwsClientSecretInvalid() {
        given(clientRepository.findByClientId(clientId)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.authenticateClient(clientId, rawSecret, "127.0.0.1"))
                .isInstanceOf(ClientSecretInvalidException.class);
        then(passwordEncoder).shouldHaveNoInteractions();
    }

    @Test
    void authenticateClient_clientDisabled_throwsClientSecretInvalid() {
        Client client = activeClient();
        client.disable();
        given(clientRepository.findByClientId(clientId)).willReturn(Optional.of(client));

        assertThatThrownBy(() -> service.authenticateClient(clientId, rawSecret, "127.0.0.1"))
                .isInstanceOf(ClientSecretInvalidException.class);
        then(passwordEncoder).shouldHaveNoInteractions();
    }

    @Test
    void authenticateClient_wrongSecret_throwsClientSecretInvalid() {
        Client client = activeClient();
        given(clientRepository.findByClientId(clientId)).willReturn(Optional.of(client));
        given(passwordEncoder.matches(rawSecret, secretHash)).willReturn(false);

        assertThatThrownBy(() -> service.authenticateClient(clientId, rawSecret, "127.0.0.1"))
                .isInstanceOf(ClientSecretInvalidException.class);
    }

    @Test
    void authenticateClient_ipNotInWhitelist_throwsIpNotAllowed() {
        Client client = activeClient();
        ClientIpEntry entry = ClientIpEntry.create(client.getId(), "10.0.0.1/32", "prod");
        given(clientRepository.findByClientId(clientId)).willReturn(Optional.of(client));
        given(passwordEncoder.matches(rawSecret, secretHash)).willReturn(true);
        given(clientIpRepository.findByClientId(client.getId())).willReturn(List.of(entry));

        assertThatThrownBy(() -> service.authenticateClient(clientId, rawSecret, "192.168.1.1"))
                .isInstanceOf(IpNotAllowedException.class);
    }

    @Test
    void authenticateClient_multipleEntriesFirstDoesNotMatch_checksAll() {
        Client client = activeClient();
        ClientIpEntry e1 = ClientIpEntry.create(client.getId(), "10.0.0.1/32", "prod");
        ClientIpEntry e2 = ClientIpEntry.create(client.getId(), "192.168.1.0/24", "dev");
        given(clientRepository.findByClientId(clientId)).willReturn(Optional.of(client));
        given(passwordEncoder.matches(rawSecret, secretHash)).willReturn(true);
        given(clientIpRepository.findByClientId(client.getId())).willReturn(List.of(e1, e2));
        given(tokenPort.generateAccessToken(any(), any(), any(), any())).willReturn("access.jwt");
        given(tokenPort.extractJti(any())).willReturn(UUID.randomUUID().toString());
        given(tokenRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        // IP is in second entry range but not first
        assertThatCode(() -> service.authenticateClient(clientId, rawSecret, "192.168.1.50"))
                .doesNotThrowAnyException();
    }

    // ── registerClient ─────────────────────────────────────────────────────

    @Test
    void registerClient_success_returnsResultWithGeneratedSecret() {
        given(clientRepository.findByClientId(clientId)).willReturn(Optional.empty());
        given(passwordEncoder.encode(anyString())).willReturn(secretHash);
        given(clientRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        ClientRegistrationResult result = service.registerClient(
                clientId, "Scoring Service", List.of("SYSTEM_SCORING"), null);

        assertThat(result.clientId()).isEqualTo(clientId);
        assertThat(result.clientName()).isEqualTo("Scoring Service");
        assertThat(result.clientSecret()).isNotBlank().hasSize(64);
        assertThat(result.status()).isEqualTo(ClientStatus.ACTIVE);
        assertThat(result.roles()).containsExactly("SYSTEM_SCORING");
        assertThat(result.expiresAt()).isNull();
        then(clientRepository).should().save(argThat(c ->
                c.getClientId().equals(clientId) && c.getSecretHash().equals(secretHash)));
    }

    @Test
    void registerClient_duplicate_throwsClientAlreadyExists() {
        Client existing = activeClient();
        given(clientRepository.findByClientId(clientId)).willReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.registerClient(clientId, "New Name", List.of(), null))
                .isInstanceOf(ClientAlreadyExistsException.class)
                .hasMessageContaining(clientId);
        then(clientRepository).should(never()).save(any());
    }

    // ── addWhitelistEntry ──────────────────────────────────────────────────

    @Test
    void addWhitelistEntry_success_savesAndReturnsEntry() {
        Client client = activeClient();
        given(clientRepository.findByClientId(clientId)).willReturn(Optional.of(client));
        given(clientIpRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        WhitelistEntryResult result = service.addWhitelistEntry(clientId, "10.0.0.0/8", "internal");

        assertThat(result.cidr()).isEqualTo("10.0.0.0/8");
        assertThat(result.label()).isEqualTo("internal");
        assertThat(result.id()).isNotNull();
        assertThat(result.createdAt()).isNotNull();
    }

    @Test
    void addWhitelistEntry_clientNotFound_throws() {
        given(clientRepository.findByClientId(clientId)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.addWhitelistEntry(clientId, "10.0.0.1/32", "label"))
                .isInstanceOf(ClientNotFoundException.class);
        then(clientIpRepository).shouldHaveNoInteractions();
    }

    // ── removeWhitelistEntry ───────────────────────────────────────────────

    @Test
    void removeWhitelistEntry_success_deletesEntry() {
        UUID entryId = UUID.randomUUID();
        ClientIpEntry entry = ClientIpEntry.create(UUID.randomUUID(), "10.0.0.1/32", "prod");
        given(clientIpRepository.findById(entryId)).willReturn(Optional.of(entry));
        willDoNothing().given(clientIpRepository).deleteById(entryId);

        service.removeWhitelistEntry(entryId);

        then(clientIpRepository).should().deleteById(entryId);
    }

    @Test
    void removeWhitelistEntry_notFound_throws() {
        UUID entryId = UUID.randomUUID();
        given(clientIpRepository.findById(entryId)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.removeWhitelistEntry(entryId))
                .isInstanceOf(ClientNotFoundException.class);
        then(clientIpRepository).should(never()).deleteById(any());
    }

    // ── listWhitelistEntries ───────────────────────────────────────────────

    @Test
    void listWhitelistEntries_returnsAllForClient() {
        Client client = activeClient();
        ClientIpEntry e1 = ClientIpEntry.create(client.getId(), "10.0.0.1/32", "prod");
        ClientIpEntry e2 = ClientIpEntry.create(client.getId(), "192.168.1.0/24", "dev");
        given(clientRepository.findByClientId(clientId)).willReturn(Optional.of(client));
        given(clientIpRepository.findByClientId(client.getId())).willReturn(List.of(e1, e2));

        List<WhitelistEntryResult> entries = service.listWhitelistEntries(clientId);

        assertThat(entries).hasSize(2);
        assertThat(entries).extracting(WhitelistEntryResult::cidr)
                .containsExactlyInAnyOrder("10.0.0.1/32", "192.168.1.0/24");
    }

    @Test
    void listWhitelistEntries_clientNotFound_throws() {
        given(clientRepository.findByClientId(clientId)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.listWhitelistEntries(clientId))
                .isInstanceOf(ClientNotFoundException.class);
    }

    // ── getClient ─────────────────────────────────────────────────────────

    @Test
    void getClient_success_returnsClientInfo() {
        Client client = activeClient();
        given(clientRepository.findByClientId(clientId)).willReturn(Optional.of(client));

        ClientInfo info = service.getClient(clientId);

        assertThat(info.clientId()).isEqualTo(clientId);
        assertThat(info.clientName()).isEqualTo("Scoring Service");
        assertThat(info.status()).isEqualTo(ClientStatus.ACTIVE);
        assertThat(info.roles()).containsExactly("SYSTEM_SCORING");
        assertThat(info.id()).isNotNull();
    }

    @Test
    void getClient_notFound_throws() {
        given(clientRepository.findByClientId(clientId)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.getClient(clientId))
                .isInstanceOf(ClientNotFoundException.class);
    }

    // ── disableClient ─────────────────────────────────────────────────────

    @Test
    void disableClient_success_setsDisabledAndSaves() {
        Client client = activeClient();
        given(clientRepository.findByClientId(clientId)).willReturn(Optional.of(client));
        given(clientRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        service.disableClient(clientId);

        assertThat(client.getStatus()).isEqualTo(ClientStatus.DISABLED);
        assertThat(client.isActive()).isFalse();
        then(clientRepository).should().save(client);
    }

    @Test
    void disableClient_notFound_throws() {
        given(clientRepository.findByClientId(clientId)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.disableClient(clientId))
                .isInstanceOf(ClientNotFoundException.class);
        then(clientRepository).should(never()).save(any());
    }

    // ── Helper ────────────────────────────────────────────────────────────

    private Client activeClient() {
        return Client.create(clientId, "Scoring Service", secretHash,
                List.of("SYSTEM_SCORING"), null);
    }
}

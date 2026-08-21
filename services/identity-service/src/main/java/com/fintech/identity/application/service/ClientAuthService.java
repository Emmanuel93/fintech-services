package com.fintech.identity.application.service;

import com.fintech.identity.application.*;
import com.fintech.identity.application.port.in.*;
import com.fintech.identity.application.port.out.*;
import com.fintech.identity.domain.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class ClientAuthService implements ClientAuthUseCase, RegisterClientUseCase,
                                           ManageClientWhitelistUseCase {

    private final ClientRepository clientRepository;
    private final ClientIpRepository clientIpRepository;
    private final TokenRepository tokenRepository;
    private final TokenPort tokenPort;
    private final PasswordEncoder passwordEncoder;
    private final AuthProperties properties;

    public ClientAuthService(ClientRepository clientRepository,
                             ClientIpRepository clientIpRepository,
                             TokenRepository tokenRepository,
                             TokenPort tokenPort,
                             PasswordEncoder passwordEncoder,
                             AuthProperties properties) {
        this.clientRepository = clientRepository;
        this.clientIpRepository = clientIpRepository;
        this.tokenRepository = tokenRepository;
        this.tokenPort = tokenPort;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
    }

    // ── Auth ──────────────────────────────────────────────────────────────

    @Override
    public TokenPair authenticateClient(String clientId, String clientSecret, String requestIp) {
        // Use filter + orElseThrow to avoid revealing whether client exists
        Client client = clientRepository.findByClientId(clientId)
                .filter(Client::isActive)
                .orElseThrow(ClientSecretInvalidException::new);

        if (!passwordEncoder.matches(clientSecret, client.getSecretHash())) {
            throw new ClientSecretInvalidException();
        }

        List<ClientIpEntry> whitelist = clientIpRepository.findByClientId(client.getId());
        if (!whitelist.isEmpty()) {
            boolean ipAllowed = whitelist.stream()
                    .anyMatch(entry -> new IpAddressMatcher(entry.getCidr()).matches(requestIp));
            if (!ipAllowed) {
                throw new IpNotAllowedException(requestIp);
            }
        }

        return issueClientTokenPair(client, requestIp);
    }

    // ── Registration ──────────────────────────────────────────────────────

    @Override
    public ClientRegistrationResult registerClient(String clientId, String clientName,
                                                   List<String> roles, Instant expiresAt) {
        if (clientRepository.findByClientId(clientId).isPresent()) {
            throw new ClientAlreadyExistsException(clientId);
        }

        String plainSecret = generateSecret();
        String secretHash = passwordEncoder.encode(plainSecret);
        Client client = Client.create(clientId, clientName, secretHash, roles, expiresAt);
        clientRepository.save(client);

        return new ClientRegistrationResult(
                client.getId(), client.getClientId(), plainSecret,
                client.getClientName(), client.getStatus(),
                client.getRoles(), client.getExpiresAt(), client.getCreatedAt());
    }

    // ── Whitelist management ──────────────────────────────────────────────

    @Override
    public WhitelistEntryResult addWhitelistEntry(String clientId, String cidr, String label) {
        Client client = clientRepository.findByClientId(clientId)
                .orElseThrow(() -> new ClientNotFoundException(clientId));
        ClientIpEntry entry = ClientIpEntry.create(client.getId(), cidr, label);
        clientIpRepository.save(entry);
        return toResult(entry);
    }

    @Override
    public void removeWhitelistEntry(UUID entryId) {
        clientIpRepository.findById(entryId)
                .orElseThrow(() -> new ClientNotFoundException("entry:" + entryId));
        clientIpRepository.deleteById(entryId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<WhitelistEntryResult> listWhitelistEntries(String clientId) {
        Client client = clientRepository.findByClientId(clientId)
                .orElseThrow(() -> new ClientNotFoundException(clientId));
        return clientIpRepository.findByClientId(client.getId()).stream()
                .map(this::toResult)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public ClientInfo getClient(String clientId) {
        Client client = clientRepository.findByClientId(clientId)
                .orElseThrow(() -> new ClientNotFoundException(clientId));
        return toInfo(client);
    }

    @Override
    public void disableClient(String clientId) {
        Client client = clientRepository.findByClientId(clientId)
                .orElseThrow(() -> new ClientNotFoundException(clientId));
        client.disable();
        clientRepository.save(client);
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private TokenPair issueClientTokenPair(Client client, String ipAddress) {
        String accessToken = tokenPort.generateAccessToken(
                client.getId(), client.getRoles(), null, Channel.SERVICE);
        String rawRefresh = generateSecret();
        String refreshHash = TokenHashing.sha256Hex(rawRefresh);
        String jti = tokenPort.extractJti(accessToken);

        AuthToken token = AuthToken.create(client.getId(), null, refreshHash, jti,
                properties.getRefreshTokenExpiryDays(), ipAddress, null, Channel.SERVICE);
        tokenRepository.save(token);

        return new TokenPair(accessToken, rawRefresh,
                (long) properties.getAccessTokenExpiryMinutes() * 60);
    }

    private String generateSecret() {
        return UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
    }


    private WhitelistEntryResult toResult(ClientIpEntry entry) {
        return new WhitelistEntryResult(entry.getId(), entry.getCidr(),
                entry.getLabel(), entry.getCreatedAt());
    }

    private ClientInfo toInfo(Client client) {
        return new ClientInfo(client.getId(), client.getClientId(), client.getClientName(),
                client.getStatus(), client.getRoles(), client.getExpiresAt(), client.getCreatedAt());
    }
}

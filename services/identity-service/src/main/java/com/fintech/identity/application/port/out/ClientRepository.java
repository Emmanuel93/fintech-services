package com.fintech.identity.application.port.out;

import com.fintech.identity.domain.Client;
import java.util.Optional;

public interface ClientRepository {
    Optional<Client> findByClientId(String clientId);
    Client save(Client client);
}

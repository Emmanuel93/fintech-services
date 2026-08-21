package com.fintech.party.domain;

import java.util.UUID;

public class PartyNotFoundException extends RuntimeException {

    public PartyNotFoundException(UUID partyId) {
        super("Party not found: " + partyId);
    }
}

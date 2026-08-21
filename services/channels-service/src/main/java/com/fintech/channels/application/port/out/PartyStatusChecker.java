package com.fintech.channels.application.port.out;

import java.util.UUID;

public interface PartyStatusChecker {

    PartyStatus check(UUID partyId, String requestingUserId);

    record PartyStatus(String status, boolean kycCompleted) {
        public boolean isBlacklisted() { return "BLACKLISTED".equals(status); }
        public boolean isActive() { return "ACTIVE".equals(status); }
    }
}

package com.fintech.identity.application.port.in;

import com.fintech.identity.application.ClientInfo;
import com.fintech.identity.application.WhitelistEntryResult;
import java.util.List;
import java.util.UUID;

public interface ManageClientWhitelistUseCase {
    WhitelistEntryResult addWhitelistEntry(String clientId, String cidr, String label);
    void removeWhitelistEntry(UUID entryId);
    List<WhitelistEntryResult> listWhitelistEntries(String clientId);
    ClientInfo getClient(String clientId);
    void disableClient(String clientId);
}

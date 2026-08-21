package com.fintech.audit;

import com.fintech.audit.application.service.AuditService;
import com.fintech.audit.infrastructure.adapter.in.messaging.IdentityEventListener;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class IdentityEventListenerTest {

    @Mock AuditService auditService;

    @Test
    void onLoginAttempted_recordsWithActorUsername_andOutcomeInEventType() {
        IdentityEventListener listener = new IdentityEventListener(auditService);
        String payload = "{\"username\":\"juanp\",\"partyId\":\"party-9\",\"outcome\":\"SUCCESS\"}";

        listener.onLoginAttempted(payload);

        verify(auditService).record(
                eq("IDENTITY_LOGIN_SUCCESS"), eq("identity-service"),
                eq("party-9"), eq("party-9"), isNull(), eq("juanp"), eq(payload));
    }

    @Test
    void onLoginAttempted_failedOutcome_stillAuditsActor() {
        IdentityEventListener listener = new IdentityEventListener(auditService);
        String payload = "{\"username\":\"attacker\",\"outcome\":\"FAILED_CREDENTIALS\"}";

        listener.onLoginAttempted(payload);

        verify(auditService).record(
                eq("IDENTITY_LOGIN_FAILED_CREDENTIALS"), eq("identity-service"),
                isNull(), isNull(), isNull(), eq("attacker"), eq(payload));
    }
}

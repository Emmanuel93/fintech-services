package com.fintech.identity.application.port.in;

import java.util.UUID;

public interface ConfirmMfaEnrollmentUseCase {

    void confirmEnrollment(UUID partyId, String totpCode);
}

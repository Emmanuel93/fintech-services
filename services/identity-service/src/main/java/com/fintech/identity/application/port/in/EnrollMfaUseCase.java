package com.fintech.identity.application.port.in;

import com.fintech.identity.application.MfaEnrollmentResult;

import java.util.UUID;

public interface EnrollMfaUseCase {

    MfaEnrollmentResult enroll(UUID partyId);
}

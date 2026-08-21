package com.fintech.origination.domain;

import com.fintech.shared.exception.DomainException;

public class PrivacyNoticeRequiredException extends DomainException {

    public PrivacyNoticeRequiredException() {
        super("PRIVACY_NOTICE_REQUIRED",
              "Prospect must accept the privacy notice before registration (LFPDPPP Art. 9)");
    }
}

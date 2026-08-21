package com.fintech.identity.domain;

import com.fintech.shared.exception.DomainException;

public class IpNotAllowedException extends DomainException {

    public IpNotAllowedException(String ip) {
        super("AUTH_IP_NOT_ALLOWED", "IP address not in client whitelist: " + ip);
    }
}

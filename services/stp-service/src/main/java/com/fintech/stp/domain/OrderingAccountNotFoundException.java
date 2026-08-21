package com.fintech.stp.domain;

import com.fintech.shared.exception.DomainException;

/** La empresa no tiene cuenta ordenante activa por default. */
public class OrderingAccountNotFoundException extends DomainException {

    public OrderingAccountNotFoundException(String message) {
        super("STP_ORDERING_ACCOUNT_NOT_FOUND", message);
    }
}

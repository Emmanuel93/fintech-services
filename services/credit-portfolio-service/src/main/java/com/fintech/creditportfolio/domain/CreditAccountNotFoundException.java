package com.fintech.creditportfolio.domain;

public class CreditAccountNotFoundException extends RuntimeException {
    public CreditAccountNotFoundException(String id) {
        super("CreditAccount not found: " + id);
    }
}

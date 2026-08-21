package com.fintech.channels.domain;

public enum DomainTarget {
    CREDIT_ORIGINATION, PAYMENTS, CREDIT_PRODUCT, SUPPORT;

    public static DomainTarget forIntent(IntentType intentType) {
        return switch (intentType) {
            case CREDIT_APPLICATION, REFINANCING -> CREDIT_ORIGINATION;
            case PAYMENT                         -> PAYMENTS;
            case ACCOUNT_INQUIRY                 -> CREDIT_PRODUCT;
            case SUPPORT                         -> SUPPORT;
        };
    }
}

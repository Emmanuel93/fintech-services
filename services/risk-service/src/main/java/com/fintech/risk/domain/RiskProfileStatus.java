package com.fintech.risk.domain;

public enum RiskProfileStatus {
    ACTIVE,   // se recalcula cada noche
    CLOSED;   // ProductSettled/ProductWrittenOff — provisionAmount congelado (RC-06)

    public boolean isClosed() { return this == CLOSED; }
}

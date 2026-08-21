package com.fintech.party.domain;

public enum PartyStatus {
    PROSPECT,       // Identidad registrada, evaluación crediticia pendiente
    ACTIVE,         // Crédito autorizado — cliente activo
    SUSPENDED,
    BLACKLISTED,
    CLOSED
}

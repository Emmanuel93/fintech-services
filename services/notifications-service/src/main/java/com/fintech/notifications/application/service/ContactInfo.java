package com.fintech.notifications.application.service;

/** Vista mínima de contacto resuelta (prospecto o party) para alimentar el dispatch. */
public record ContactInfo(String firstName, String phone, String email) {
    public static ContactInfo empty() { return new ContactInfo(null, null, null); }
}

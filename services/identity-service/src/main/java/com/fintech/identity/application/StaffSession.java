package com.fintech.identity.application;

/** Sesión de backoffice recién emitida: los tokens y quién es el empleado que los recibió. */
public record StaffSession(TokenPair tokens, StaffProfile profile) {}

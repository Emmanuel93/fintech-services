package com.fintech.channelmobile.application;

import java.time.Instant;

public record KycSessionData(
        String phone,
        String nombres,
        String apellidoPaterno,
        String apellidoMaterno,
        String curp,
        String rfc,
        String fechaNacimiento,
        String genero,
        String estadoNacimiento,
        String email,
        String calle,
        String numeroExterior,
        String numeroInterior,
        String colonia,
        String municipio,
        String ciudad,
        String estado,
        String codigoPostal,
        boolean aceptaAvisoPrivacidad,
        boolean aceptaCirculo,
        Instant expiresAt
) {
    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }
}

package com.fintech.channelmobile.infrastructure.adapter.in.api.dto;

/**
 * Perfil en el shape que consume la app (fa_profile.ProfileDto).
 *
 * <p>Se arma con **dos** fuentes porque el dato está partido en dos dominios: party-service
 * tiene la identidad ya verificada —nombre, CURP, RFC, estatus KYC— y origination guarda el
 * expediente tal como la persona lo capturó: su domicilio, su género, el celular que verificó
 * con el código. La pantalla de perfil los enseña juntos, así que el BFF los junta aquí y no
 * obliga a la app a pedir dos veces y coserlos.
 *
 * <p>Los campos que falten llegan vacíos, nunca nulos: la app los oculta en vez de pintar un
 * renglón sin valor.
 */
public record ProfileResponse(
        String id,
        String fullName,
        String phone,
        String email,
        String curp,
        String rfc,
        String kycStatus,
        String memberSince,
        String dateOfBirth,
        String state,

        // ── Expediente del alta (origination) ────────────────────────────────
        String gender,
        String stateOfBirth,
        String street,
        String exteriorNumber,
        String interiorNumber,
        String neighborhood,
        String municipality,
        String city,
        String postalCode
) {}

package com.fintech.beneficiary.application;

import java.math.BigDecimal;

/**
 * «Tus datos» (paso 5): lo que ella confirma de su INE y lo que sólo ella sabe.
 *
 * <p>La CLABE va aquí y es obligatoria (§3.1): el diseño no pedía cuenta bancaria, pero la
 * disposición {@code THIRD_PARTY_CREDIT} exige a dónde depositar, y sin CLABE no hay submit.
 *
 * <p>{@code occupation} y {@code monthlyIncome} no viajan a origination —su alta de prospecto no
 * los recibe— pero se guardan en la sesión y los ve la mesa de KYC: son lo primero que un
 * analista contrasta contra el monto que la distribuidora le quiere colocar.
 */
public record KycData(
        String firstName,
        String lastName1,
        String lastName2,
        String curp,
        String rfc,
        String dateOfBirth,
        String occupation,
        BigDecimal monthlyIncome,
        String street,
        String exteriorNumber,
        String neighborhood,
        String municipality,
        String city,
        String state,
        String postalCode,
        String clabe,
        String email) {}

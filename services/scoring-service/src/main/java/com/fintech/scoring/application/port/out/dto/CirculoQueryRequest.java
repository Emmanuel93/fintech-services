package com.fintech.scoring.application.port.out.dto;

import java.time.LocalDate;

public record CirculoQueryRequest(
        String apellidoPaterno,
        String apellidoMaterno,
        String primerNombre,
        String curp,
        String rfc,
        LocalDate fechaNacimiento,
        String street,
        String exteriorNumber,
        String interiorNumber,
        String neighborhood,
        String municipality,
        String city,
        String state,
        String postalCode
) {}

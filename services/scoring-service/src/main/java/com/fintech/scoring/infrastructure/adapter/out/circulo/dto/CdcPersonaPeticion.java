package com.fintech.scoring.infrastructure.adapter.out.circulo.dto;

public record CdcPersonaPeticion(
        String apellidoPaterno,
        String apellidoMaterno,
        String apellidoAdicional,
        String primerNombre,
        String segundoNombre,
        String fechaNacimiento,
        String RFC,
        String CURP,
        String nacionalidad,
        CdcDomicilioPeticion domicilio
) {}

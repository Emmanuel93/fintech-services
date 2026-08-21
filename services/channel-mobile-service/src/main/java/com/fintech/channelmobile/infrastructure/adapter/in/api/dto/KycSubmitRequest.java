package com.fintech.channelmobile.infrastructure.adapter.in.api.dto;

import jakarta.validation.constraints.*;

public record KycSubmitRequest(

        @NotBlank
        @Pattern(regexp = "^\\d{10}$", message = "El teléfono debe tener 10 dígitos")
        String phone,

        @NotBlank @Size(max = 100)
        String nombres,

        @NotBlank @Size(max = 100)
        String apellidoPaterno,

        @Size(max = 100)
        String apellidoMaterno,

        @NotBlank
        @Pattern(regexp = "^[A-Z]{4}\\d{6}[HM][A-Z]{5}[A-Z0-9]\\d$",
                 message = "Formato de CURP inválido")
        String curp,

        @Pattern(regexp = "^[A-Z&Ñ]{3,4}\\d{6}[A-Z0-9]{3}$",
                 message = "Formato de RFC inválido")
        String rfc,

        @NotBlank
        String fechaNacimiento,

        @NotBlank
        @Pattern(regexp = "^[HMXhmx]$", message = "Género debe ser H, M o X")
        String genero,

        @NotBlank @Size(max = 100)
        String estadoNacimiento,

        @Email @Size(max = 254)
        String email,

        // Domicilio — acepta cadena OCR o campos estructurados
        String domicilio,

        @Size(max = 200)
        String calle,

        @Size(max = 20)
        String numeroExterior,

        @Size(max = 20)
        String numeroInterior,

        @Size(max = 100)
        String colonia,

        @Size(max = 100)
        String municipio,

        @Size(max = 100)
        String ciudad,

        @Size(max = 100)
        String estado,

        @Pattern(regexp = "^\\d{5}$", message = "Código postal debe tener 5 dígitos")
        String codigoPostal,

        @AssertTrue(message = "Debe aceptar el aviso de privacidad (LFPDPPP Art. 9)")
        boolean aceptaAvisoPrivacidad,

        boolean aceptaCirculo
) {}

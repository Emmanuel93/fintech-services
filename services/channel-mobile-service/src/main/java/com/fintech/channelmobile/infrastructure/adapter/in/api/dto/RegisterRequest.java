package com.fintech.channelmobile.infrastructure.adapter.in.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/** El teléfono y datos del prospecto se toman de la sesión KYC asociada al folioKyc. */
public record RegisterRequest(

        @NotBlank
        @Size(min = 8, max = 100, message = "La contraseña debe tener al menos 8 caracteres")
        String password,

        @NotBlank
        String folioKyc,

        /**
         * El expediente que la app capturó en el paso de documentos.
         *
         * <p>Viaja aquí y no en una llamada aparte porque este es el único momento en que existen
         * a la vez las dos cosas que hacen falta: los archivos, que hasta ahora sólo vivían en el
         * teléfono, y la autorización para asociarlos —el folioKyc, de un solo uso y recién
         * validado por OTP—. Después del alta el prospecto ya existe y subir a su expediente
         * exigiría abrir una ventana de autorización nueva sobre datos personales.
         *
         * <p>Opcional: un alta sin documentos sigue siendo un alta. Lo que falte se pide luego
         * con {@code PUT /kyc/documents/{tipo}}, que es el camino de PENDING_DOCUMENTS.
         */
        @Valid
        List<DocumentUpload> documents
) {}

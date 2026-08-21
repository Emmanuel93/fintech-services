package com.fintech.origination.infrastructure.adapter.in.api.dto;

import com.fintech.origination.domain.IncomeProofType;
import com.fintech.origination.domain.ProspectDocumentType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Un documento del expediente en el alta.
 *
 * <p>{@code documentRef} declara que se entregó; los tres campos de contenido traen el archivo.
 * Van juntos en el alta y no en una llamada aparte porque es el único momento en que existen a la
 * vez el archivo —que hasta entonces sólo vive en el teléfono— y la autorización para asociarlo:
 * el alta de prospecto es pública porque cualquiera puede darse de alta, pero escribir en el
 * expediente de un prospecto <b>ya creado</b> no puede serlo. Separarlos obligaría a abrir un
 * endpoint público que acepta un UUID ajeno.
 *
 * <p>El contenido es opcional: un alta sin archivos sigue siendo un alta, y lo que falte se
 * completa después con sesión iniciada.
 */
public record ProspectDocumentRequest(
        @NotNull
        ProspectDocumentType documentType,

        @NotBlank @Size(max = 500)
        String documentRef,

        IncomeProofType incomeProofType,

        @Size(max = 255) String fileName,
        @Size(max = 120) String contentType,
        String contentBase64
) {
    /** Si trae archivo o sólo la declaración de que existe. */
    public boolean hasContent() {
        return contentBase64 != null && !contentBase64.isBlank();
    }
}

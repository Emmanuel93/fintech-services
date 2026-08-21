package com.fintech.origination.application.port.in;

import com.fintech.origination.domain.Prospect;

import java.util.List;
import java.util.UUID;

/** Lectura del prospecto para el backoffice (mesa de análisis): lo que capturó el sujeto. */
public interface FindProspectUseCase {

    Prospect getById(UUID prospectId);

    /** Prospectos con ese correo, teléfono o CURP exactos. Vacío si no hay ninguno. */
    List<Prospect> findByContact(String email, String phone, String curp);
}

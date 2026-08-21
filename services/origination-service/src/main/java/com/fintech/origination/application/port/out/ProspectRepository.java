package com.fintech.origination.application.port.out;

import com.fintech.origination.domain.Prospect;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProspectRepository {

    Prospect save(Prospect prospect);

    Optional<Prospect> findById(UUID prospectId);

    boolean existsByCurp(String curp);

    boolean existsByPhone(String phone);

    /**
     * Prospectos que coinciden con un dato de contacto exacto.
     *
     * Exacto y no parcial a propósito: quien audita busca «este teléfono», no «algo
     * parecido a este teléfono», y una coincidencia parcial sobre datos personales
     * convierte una consulta puntual en un listado de gente que no se pidió.
     */
    List<Prospect> findByContact(String email, String phone, String curp);
}

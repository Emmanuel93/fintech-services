package com.fintech.invoicing.application.port.out;

import com.fintech.invoicing.domain.FiscalProfile;

import java.util.Optional;
import java.util.UUID;

public interface FiscalProfileRepository {
    Optional<FiscalProfile> findById(UUID partyId);

    /**
     * El perfil del cliente buscado por el id del <b>prospecto</b>.
     *
     * <p>Es el id que traen las facturas: el crédito nace de una solicitud y arrastra el del
     * prospecto, no el del party creado después.
     */
    Optional<FiscalProfile> findByProspectId(UUID prospectId);

    FiscalProfile save(FiscalProfile profile);
}

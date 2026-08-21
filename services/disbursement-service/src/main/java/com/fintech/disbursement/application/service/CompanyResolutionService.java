package com.fintech.disbursement.application.service;

import com.fintech.disbursement.application.RequestDisbursementCommand;
import com.fintech.disbursement.application.port.out.CompanyMappingRepository;
import com.fintech.disbursement.domain.CompanyMapping;
import com.fintech.disbursement.domain.UnresolvedCompanyException;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Resuelve la empresa dueña del pago (DB-07).
 *
 * <p>Dos vías, en este orden: el emisor ya trae el {@code companyId} de este servicio, o trae una
 * clave propia que se traduce contra {@code company_mappings}. <strong>No hay tercera.</strong> Si
 * no se puede resolver, la orden no se crea: adivinar la empresa significa firmar con la llave
 * equivocada y sacar dinero de la cuenta equivocada.
 */
@Service
public class CompanyResolutionService {

    private final CompanyMappingRepository mappings;

    public CompanyResolutionService(CompanyMappingRepository mappings) {
        this.mappings = mappings;
    }

    public UUID resolve(RequestDisbursementCommand command) {
        if (command.companyId() != null) {
            return command.companyId();
        }
        String key = command.sourceCompanyKey();
        if (key == null || key.isBlank()) {
            throw new UnresolvedCompanyException(
                    "El evento de " + command.sourceSystem() + " no trae companyId ni sourceCompanyKey"
                            + " (sourceEventId=" + command.sourceEventId() + ")");
        }
        return mappings.find(command.sourceSystem(), key)
                .filter(CompanyMapping::isEnabled)
                .map(CompanyMapping::getCompanyId)
                .orElseThrow(() -> new UnresolvedCompanyException(
                        "No hay mapeo habilitado para sourceSystem=" + command.sourceSystem()
                                + " sourceKey=" + key));
    }
}

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
 * <p>Tres vías, en este orden: el emisor ya trae el {@code companyId} de este servicio; trae una
 * clave propia que se traduce contra {@code company_mappings}; o el sistema origen tiene sembrado
 * un comodín {@code *}, que es la empresa por omisión de ese sistema. <strong>No hay cuarta.</strong>
 * Si no se puede resolver, la orden no se crea: adivinar la empresa significa firmar con la llave
 * equivocada y sacar dinero de la cuenta equivocada.
 *
 * <p>El comodín <b>no</b> es adivinar: alguien lo sembró a propósito diciendo «para este sistema
 * origen, ésta es la empresa». Lo que sí era un problema es tenerlo sembrado y no dejarlo llegar.
 */
@Service
public class CompanyResolutionService {

    private final CompanyMappingRepository mappings;

    public CompanyResolutionService(CompanyMappingRepository mappings) {
        this.mappings = mappings;
    }

    /** Clave de respaldo: cubre a todo el sistema origen que no tenga mapeo propio. */
    public static final String COMODIN = "*";

    public UUID resolve(RequestDisbursementCommand command) {
        if (command.companyId() != null) {
            return command.companyId();
        }
        String key = command.sourceCompanyKey();
        if (key == null || key.isBlank()) {
            // Sin clave, el comodín es la respuesta — no un caso a rechazar antes de mirarlo.
            //
            // Rechazar aquí dejaba el comodín inalcanzable justo cuando era su turno: `*` dice
            // "para este sistema origen, ésta es la empresa", y no traer clave es la forma más
            // pura de "no tengo mapeo propio". Con la guarda delante, TODA disposición del
            // sistema murió en la DLT con UNRESOLVED_COMPANY mientras el comodín estaba
            // sembrado y habilitado, esperando una llamada que la guarda no dejaba llegar.
            //
            // Sigue sin adivinarse nada: si nadie sembró el comodín, esto lanza igual.
            return comodin(command).orElseThrow(() -> new UnresolvedCompanyException(
                    "El evento de " + command.sourceSystem() + " no trae companyId ni sourceCompanyKey,"
                            + " y no hay comodín '" + COMODIN + "' habilitado para ese sistema"
                            + " (sourceEventId=" + command.sourceEventId() + ")"));
        }
        return mappings.find(command.sourceSystem(), key)
                .filter(CompanyMapping::isEnabled)
                .map(CompanyMapping::getCompanyId)
                // Sin mapeo específico se cae al comodín del sistema origen.
                //
                // Es lo que evita que dar de alta una sucursal nueva rompa sus pagos en silencio,
                // el día que alguien coloque el primer crédito ahí y nadie recuerde que había que
                // mapearla. Un mapeo específico siempre gana; el comodín sólo cubre el hueco.
                .or(() -> comodin(command))
                .orElseThrow(() -> new UnresolvedCompanyException(
                        "No hay mapeo habilitado para sourceSystem=" + command.sourceSystem()
                                + " sourceKey=" + key + " (ni comodín '" + COMODIN + "')"));
    }

    private java.util.Optional<UUID> comodin(RequestDisbursementCommand command) {
        return mappings.find(command.sourceSystem(), COMODIN)
                .filter(CompanyMapping::isEnabled)
                .map(CompanyMapping::getCompanyId);
    }
}

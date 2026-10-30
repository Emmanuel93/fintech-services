package com.fintech.compliance.application.port.out;

import com.fintech.compliance.domain.IdentityStatus;
import com.fintech.compliance.domain.PersonVerification;
import com.fintech.compliance.domain.ScreeningStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Set;

/**
 * La cola de la mesa de cumplimiento.
 *
 * <p>Puerto aparte de {@link PersonVerificationRepository} porque responde otra pregunta: aquél sirve
 * a las compuertas —una persona, por su llave— y éste a quien trabaja la cola —muchas, por su
 * estado—. Mientras no haya proveedor de KYC, el 100 % de las altas pasa por aquí, así que sin esta
 * consulta el dictamen manual existía y no había cómo encontrar a quién dictaminar.
 */
public interface VerificationQueueRepository {

    /**
     * @param identity  estados de identidad a incluir; vacío = todos
     * @param screening estados de listas a incluir; vacío = todos
     * @param text      nombre o CURP, parcial y sin distinguir mayúsculas; nulo = sin filtro
     */
    Page<PersonVerification> search(Set<IdentityStatus> identity, Set<ScreeningStatus> screening,
                                    String text, Pageable pageable);
}

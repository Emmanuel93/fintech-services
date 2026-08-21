package com.fintech.collections.application.port.in;

import com.fintech.collections.domain.CollectionAgreement;
import com.fintech.collections.domain.ContactAttempt;
import com.fintech.collections.domain.PaymentPromise;
import com.fintech.collections.domain.WriteOffRecord;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * La gestión acumulada de un caso: lo que ya se hizo, no lo que se puede hacer.
 *
 * <p>Existe porque cobranza sólo sabía <em>escribir</em> gestión. Se podía registrar un contacto o
 * una promesa, y la respuesta del POST era la única vez que ese dato se veía: al recargar la
 * pantalla desaparecía. Un gestor que retoma un caso al día siguiente necesita justamente lo
 * contrario —cuántas veces se llamó, qué contestaron, qué se prometió y si se cumplió—, que es
 * también lo que revisa CONDUSEF.
 */
public interface CaseTimelineUseCase {

    List<ContactAttempt> contactAttempts(UUID caseId);

    /**
     * Intentos de hoy, con el mismo corte de día que usa la regla CT-03 al rechazar el cuarto.
     *
     * <p>Se expone precisamente para que la pantalla pueda decir «2 de 3» sin recalcularlo: una
     * cuenta paralela en el cliente se desincroniza en cuanto haya dos gestores en el mismo caso,
     * y el usuario descubre el tope cuando el backend le contesta 422.
     */
    long contactAttemptsToday(UUID caseId);

    List<PaymentPromise> paymentPromises(UUID caseId);

    /** Historial completo, incluidos rechazados y expirados. */
    List<CollectionAgreement> agreements(UUID caseId);

    /** Bandeja de cuatro ojos: aceptados por el deudor, a la espera de quien autoriza. */
    List<CollectionAgreement> agreementsAwaitingAuthorization();

    Optional<WriteOffRecord> writeOffByAccount(UUID creditAccountId);
}

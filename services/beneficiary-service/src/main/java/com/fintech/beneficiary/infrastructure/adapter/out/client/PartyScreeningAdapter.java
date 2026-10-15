package com.fintech.beneficiary.infrastructure.adapter.out.client;

import com.fintech.beneficiary.application.port.out.PersonScreeningReader;
import com.fintech.beneficiary.domain.ScreeningVerdict;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Lee el veredicto de listas de la ficha que {@code party} ya expone.
 *
 * <p>No añade una llamada nueva al camino de la aprobación: es el mismo
 * {@code GET /parties/by-prospect/{id}} que este servicio usa para todo lo demás, y la verificación
 * viaja en esa misma respuesta.
 */
@Component
public class PartyScreeningAdapter implements PersonScreeningReader {

    private static final Logger log = LoggerFactory.getLogger(PartyScreeningAdapter.class);

    private final PartyClient party;

    public PartyScreeningAdapter(PartyClient party) {
        this.party = party;
    }

    @Override
    public ScreeningVerdict screeningOf(UUID beneficiaryProspectId) {
        if (beneficiaryProspectId == null) {
            // Una colocación sin prospecto es una cuya beneficiaria no completó su alta. No hay a
            // quién haber comprobado, así que no se puede afirmar que esté limpia.
            return ScreeningVerdict.unavailable("la colocación no tiene prospecto de beneficiaria");
        }

        var record = party.findByProspect(beneficiaryProspectId);
        if (record.isEmpty()) {
            log.warn("No se pudo leer la ficha del prospecto {} — la colocación queda bloqueada por "
                     + "listas sin veredicto", beneficiaryProspectId);
            return ScreeningVerdict.unavailable("party no entregó la ficha de la persona");
        }

        String status = record.get().screeningStatus();
        if (status == null || status.isBlank()) {
            // Party contestó pero sin el campo: es una versión vieja del servicio, o la persona
            // nunca se verificó. Las dos cosas significan lo mismo aquí — no se sabe.
            return ScreeningVerdict.unavailable("la ficha no trae estado de listas");
        }

        return new ScreeningVerdict(status,
                ScreeningVerdict.CLEARED.equals(status) ? null
                        : blockReasonOf(record.get()));
    }

    private static String blockReasonOf(PartyClient.PartyResponse record) {
        // Se prefiere el motivo que escribió cumplimiento; si no lo hay, se construye uno que al
        // menos nombre el estado, para que el rechazo nunca llegue sin explicación.
        String reason = record.verificationBlockReason();
        return reason == null || reason.isBlank()
                ? "la beneficiaria no está despejada de listas (" + record.screeningStatus() + ")"
                : reason;
    }
}

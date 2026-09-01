package com.fintech.collections.application.port.out;

import com.fintech.collections.domain.BureauEventType;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Reporta un quebranto o una quita a Círculo de Crédito.
 *
 * <p>El comentario anterior lo comparaba con {@code SpeiDispatchPort} y {@code WalletDispatchPort},
 * que se eliminaron en BK-11 y BK-12 por ser despachadores paralelos que confirmaban sin haber
 * hecho nada. <b>Éste no lo es</b>: reportar al buró sí es su propio camino, y no duplica ninguno.
 */
public interface BureauReportingAdapter {
    /** @return the bureau's confirmation reference. Throws if the submission fails (caller marks FAILED). */
    String submit(UUID creditAccountId, UUID obligorPartyId, BureauEventType eventType, BigDecimal amount);
}

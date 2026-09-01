package com.fintech.banking.application.port.out;

import com.fintech.banking.domain.MovementTrace;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MovementTraceRepository {

    MovementTrace save(MovementTrace traza);

    Optional<MovementTrace> findByPayoutId(UUID payoutId);

    Optional<MovementTrace> findByTrackingKey(String trackingKey);

    Optional<MovementTrace> findByLineId(UUID lineId);

    List<MovementTrace> findByCreditAccountId(UUID creditAccountId);

    List<MovementTrace> findByVoucherRef(String voucherRef);

    /** Las que se quedaron a medias. Es el reporte que alguien mira cada mañana. */
    List<MovementTrace> findIncompletas(int limite);
}

package com.fintech.banking.application.port.out;

import com.fintech.banking.domain.PayoutRoute;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PayoutRouteRepository {

    PayoutRoute save(PayoutRoute ruta);

    Optional<PayoutRoute> findById(UUID id);

    /** Todas las habilitadas. El filtrado fino se hace en memoria: el catálogo es de decenas de filas. */
    List<PayoutRoute> findAllEnabled();

    List<PayoutRoute> findAll();
}

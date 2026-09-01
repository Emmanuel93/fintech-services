package com.fintech.banking.application.port.in;

import com.fintech.banking.domain.PayoutProvider;
import com.fintech.banking.domain.PayoutRail;
import com.fintech.banking.domain.PayoutRoute;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Alta y baja de rutas. Cambiar por dónde sale el dinero es un {@code INSERT}, no un despliegue. */
public interface ManagePayoutRoutesUseCase {

    record AltaDeRuta(UUID companyId,
                      PayoutRail rail,
                      PayoutProvider provider,
                      UUID bankAccountId,
                      BigDecimal minAmount,
                      BigDecimal maxAmount,
                      Integer priority) {}

    PayoutRoute alta(AltaDeRuta alta);

    List<PayoutRoute> listar();

    PayoutRoute deshabilitar(UUID id);
}

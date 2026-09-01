package com.fintech.banking.infrastructure.adapter.in.api;

import com.fintech.banking.domain.PayoutRoute;

import java.math.BigDecimal;
import java.util.UUID;

/** La ruta como configuración. No lleva CLABE: para eso está {@code POST /payouts/route}. */
public record PayoutRouteResponse(UUID payoutRouteId,
                                  UUID companyId,
                                  String rail,
                                  String provider,
                                  UUID bankAccountId,
                                  BigDecimal minAmount,
                                  BigDecimal maxAmount,
                                  int priority,
                                  boolean enabled) {

    static PayoutRouteResponse de(PayoutRoute r) {
        return new PayoutRouteResponse(r.getId(), r.getCompanyId(), r.getRail(), r.getProvider(),
                r.getBankAccountId(), r.getMinAmount(), r.getMaxAmount(), r.getPriority(),
                r.isEnabled());
    }
}

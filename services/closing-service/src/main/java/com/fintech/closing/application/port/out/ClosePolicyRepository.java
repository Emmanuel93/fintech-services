package com.fintech.closing.application.port.out;

import com.fintech.closing.domain.ClosePolicy;

import java.util.List;

public interface ClosePolicyRepository {
    /** Todas las ACTIVE de los alcances candidatos. La precedencia la decide el dominio. */
    List<ClosePolicy> findActiveForScopes(List<String> productIds, List<String> productTypes);
    List<ClosePolicy> findAllActive();
}

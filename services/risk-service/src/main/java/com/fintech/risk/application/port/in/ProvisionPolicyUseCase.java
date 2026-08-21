package com.fintech.risk.application.port.in;

import com.fintech.risk.application.CreateProvisionPolicyCommand;
import com.fintech.risk.domain.ProvisionPolicy;

import java.util.List;

public interface ProvisionPolicyUseCase {
    /** Crea una nueva versión ACTIVE y deprecia la anterior para ese productType (PP-01). */
    ProvisionPolicy create(CreateProvisionPolicyCommand command);
    List<ProvisionPolicy> listActive();
    ProvisionPolicy getActiveByProductType(String productType);
}

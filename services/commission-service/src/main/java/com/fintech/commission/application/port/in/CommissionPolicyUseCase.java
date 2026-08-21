package com.fintech.commission.application.port.in;

import com.fintech.commission.application.CreateCommissionPolicyCommand;
import com.fintech.commission.domain.CommissionPolicy;

import java.util.List;

public interface CommissionPolicyUseCase {
    CommissionPolicy create(CreateCommissionPolicyCommand command);
    List<CommissionPolicy> listActive();
}

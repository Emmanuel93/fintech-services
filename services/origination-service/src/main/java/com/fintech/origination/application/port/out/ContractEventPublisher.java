package com.fintech.origination.application.port.out;

import com.fintech.origination.domain.event.ContractSignedEvent;
import com.fintech.origination.domain.event.CreditProductCreationRequestedEvent;

public interface ContractEventPublisher {
    void publishContractSigned(ContractSignedEvent event);
    void publishCreditProductCreationRequested(CreditProductCreationRequestedEvent event);
}

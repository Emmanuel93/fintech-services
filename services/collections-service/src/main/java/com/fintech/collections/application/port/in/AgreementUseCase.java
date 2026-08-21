package com.fintech.collections.application.port.in;

import com.fintech.collections.application.ProposeAgreementCommand;
import com.fintech.collections.domain.CollectionAgreement;
import java.util.UUID;

public interface AgreementUseCase {
    CollectionAgreement propose(ProposeAgreementCommand command);
    CollectionAgreement accept(UUID agreementId);
    CollectionAgreement reject(UUID agreementId);
    /** AG-02: requires explicit authorization before EXECUTED. */
    CollectionAgreement authorize(UUID agreementId, String authorizedBy, String authorizationRef);
}

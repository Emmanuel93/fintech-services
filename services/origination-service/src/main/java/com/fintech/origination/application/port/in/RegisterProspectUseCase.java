package com.fintech.origination.application.port.in;

import com.fintech.origination.application.RegisterProspectCommand;
import com.fintech.origination.application.RegisterProspectResult;

public interface RegisterProspectUseCase {

    RegisterProspectResult register(RegisterProspectCommand command);
}

package com.fintech.disbursement.application.port.in;

import com.fintech.disbursement.domain.DisbursementOrder;

import java.util.UUID;

public interface CancelDisbursementUseCase {

    /** Sólo antes de despachar. Una vez que el proveedor la tiene, cancelar es mentir. */
    DisbursementOrder cancel(UUID disbursementId, String reason, String actor);
}

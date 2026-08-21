package com.fintech.creditportfolio.application.port.in;

import java.util.UUID;

/**
 * Cierre del ciclo de desembolso. disbursement-service confirma (o falla) el pago que se le pidió
 * en credit-account-activated / disposition-authorized, y aquí la disposición pasa de PROCESSING a
 * su estado terminal — con el {@code externalRef} real, no el "SPEI-STUB-…" que se ponía antes de
 * que saliera un peso.
 */
public interface SettleDisbursementUseCase {

    /** El dinero llegó, con evidencia. Completa la disposición y publica disposition-completed. */
    void onDisbursementCompleted(UUID dispositionId, String externalRef);

    /** El dinero no va a llegar. Marca la disposición como fallida. */
    void onDisbursementFailed(UUID dispositionId, String failureCode, String failureReason);
}

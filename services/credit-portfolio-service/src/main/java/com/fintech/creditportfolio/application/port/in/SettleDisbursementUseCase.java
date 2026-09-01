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

    /**
     * El banco receptor devolvió el dinero (BK-16).
     *
     * <p>Es distinto de un fallo: el pago salió, llegó al banco del beneficiario y volvió — cuenta
     * cancelada, nombre que no coincide, lo que sea. Cartera se tiene que enterar, y hasta ahora no
     * había quien escuchara {@code disbursement.returned}: el cliente quedaba debiendo un dinero
     * que el banco ya había devuelto.
     */
    void onDisbursementReturned(UUID dispositionId, String reason);
}

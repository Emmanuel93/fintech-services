package com.fintech.creditportfolio.application.port.in;

import java.util.UUID;

/**
 * Cierre del ciclo de desembolso. disbursement-service confirma (o falla) el pago que se le pidió
 * en credit-account-activated / disposition-authorized, y aquí la disposición pasa de PROCESSING a
 * su estado terminal — con el {@code externalRef} real, no el "SPEI-STUB-…" que se ponía antes de
 * que saliera un peso.
 *
 * <p><b>D63.</b> A payment that fails is asked again while another attempt may overcome the failure; one that cannot
 * happen, or comes back, is compensated at once: the principal and the available credit go back, the plan is
 * cancelled, and an installment credit that never lent anything is cancelled with every service compensating.
 */
public interface SettleDisbursementUseCase {

    /** El dinero llegó, con evidencia. Completa la disposición y publica disposition-completed. */
    void onDisbursementCompleted(UUID dispositionId, int attempt, String externalRef);

    /** An attempt's payment did not happen: asked again if another may work, compensated if not. */
    void onDisbursementFailed(DisbursementFailure failure);

    /**
     * El banco receptor devolvió el dinero (BK-16): the payment left, reached the beneficiary's bank and came back.
     * Compensated as a payment that did not happen.
     */
    void onDisbursementReturned(UUID dispositionId, String reason);

    /**
     * What disbursement said about an attempt that did not pay.
     *
     * @param attempt   which attempt of the disposition's payment it was
     * @param retryable whether another attempt may succeed (a technical or configuration failure, not a rule)
     */
    record DisbursementFailure(UUID dispositionId, int attempt, String failureCode, String failureReason,
                               boolean retryable) {}
}

package com.fintech.origination.domain;

import com.fintech.shared.exception.DomainException;

/**
 * El promoterCode capturado no corresponde a ningún distribuidor. Se rechaza la solicitud en vez de
 * originar un crédito que nunca acreditaría comisión (desactiva la bomba CM-07).
 */
public class PromoterCodeNotResolvableException extends DomainException {
    public PromoterCodeNotResolvableException(String promoterCode) {
        super("PROMOTER_CODE_NOT_RESOLVABLE",
              "El código de promotor/distribuidor no existe: " + promoterCode);
    }
}

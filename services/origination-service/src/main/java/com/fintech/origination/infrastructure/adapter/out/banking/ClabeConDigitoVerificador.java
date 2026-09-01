package com.fintech.origination.infrastructure.adapter.out.banking;

import com.fintech.origination.application.port.out.ClabeValidator;
import com.fintech.shared.banking.ClabeCheckDigit;
import org.springframework.stereotype.Component;

/**
 * Valida la CLABE en el punto donde entra al sistema: la firma del contrato.
 *
 * <p>Antes había aquí un stub que comprobaba <b>dieciocho dígitos y nada más</b>. La llamada
 * existía, el código de error existía ({@code CM-06}), y no verificaba lo único que un dígito
 * verificador sirve para verificar. El resultado es que un error de captura en la cuenta del cliente
 * atravesaba entera la originación —scoring, oferta, contrato, firma, alta— y sólo lo cazaba
 * {@code disbursement} al ir a mandar el dinero, con el crédito ya otorgado y activo.
 *
 * <p>Ahí es tarde y de la peor manera: la cuenta existe, debe, devenga, y el desembolso muere en la
 * cola de mensajes. El cliente firmó un crédito que nunca va a recibir, y quien lo atiende no tiene
 * de dónde saberlo.
 *
 * <p>El dígito verificador es <b>local y determinista</b> — el algoritmo de Banxico, el mismo que ya
 * usan tesorería y el conector. No hace falta contrato con nadie para dejar de aceptar una CLABE que
 * no puede existir. Lo que sí exige integración —que la cuenta esté abierta y admita abonos— sigue
 * pendiente y no lo suple esto.
 */
@Component
public class ClabeConDigitoVerificador implements ClabeValidator {

    @Override
    public boolean isValid(String clabeAccount) {
        return ClabeCheckDigit.esValida(clabeAccount);
    }
}

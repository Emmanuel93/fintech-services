package com.fintech.origination.infrastructure.adapter.out.stub;

import com.fintech.origination.application.port.out.SignatureValidator;
import com.fintech.shared.simulation.SimulatorGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * La firma electrónica mientras no hay proveedor (Incode, e.firma, NIP).
 *
 * <h2>Por qué ya no es un {@code return true}</h2>
 *
 * Un validador que aprueba todo no es un simulador: es la ausencia del control. Ninguna prueba podía
 * ejercitar «la firma no es válida», y en producción cualquier cadena contaba como firma. Este
 * simula al proveedor, <b>incluido decir que no</b>, con escenarios deterministas por la prueba de
 * firma que manda la app (hoy, el código OTP que la persona teclea al firmar):
 *
 * <table>
 *   <tr><th>Prueba de firma</th><th>Resultado</th></tr>
 *   <tr><td>vacía</td><td>inválida</td></tr>
 *   <tr><td>{@code 000000} (o termina en {@code -000000})</td><td>inválida: el proveedor la rechaza</td></tr>
 *   <tr><td>cualquier otra</td><td>válida</td></tr>
 * </table>
 *
 * <h2>Cómo entra el proveedor real</h2>
 *
 * Por el mismo puerto {@link SignatureValidator}, con
 * {@code fintech.origination.signature.provider=<proveedor>}: este bean sólo existe con
 * {@code simulated} (el valor por defecto) y su guarda le impide arrancar fuera de un ambiente bajo.
 * Un ambiente productivo sin adaptador real no arranca por falta del bean, que es lo correcto.
 */
@Component
@ConditionalOnProperty(name = "fintech.origination.signature.provider", havingValue = "simulated", matchIfMissing = true)
public class SimulatedSignatureValidator implements SignatureValidator {

    private static final Logger log = LoggerFactory.getLogger(SimulatedSignatureValidator.class);
    static final String REJECTED_PROOF = "000000";

    @Autowired
    void refuseToRunInProduction(Environment environment) {
        SimulatorGuard.requireNonProduction(environment, "SimulatedSignatureValidator (firma electrónica simulada)",
                "fintech.origination.signature.provider=<proveedor> con su adaptador de SignatureValidator");
    }

    @Override
    public boolean isValid(String signatureMethod, String signatureProof) {
        if (signatureProof == null || signatureProof.isBlank()) {
            log.warn("[FIRMA SIMULADA] prueba de firma vacía method={} → inválida", signatureMethod);
            return false;
        }
        String proof = signatureProof.trim();
        if (proof.equals(REJECTED_PROOF) || proof.endsWith("-" + REJECTED_PROOF)) {
            log.warn("[FIRMA SIMULADA] escenario de rechazo method={} → inválida", signatureMethod);
            return false;
        }
        log.debug("[FIRMA SIMULADA] firma aceptada sin proveedor method={}", signatureMethod);
        return true;
    }
}

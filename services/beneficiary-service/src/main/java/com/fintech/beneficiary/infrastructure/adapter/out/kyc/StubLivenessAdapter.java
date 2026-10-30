package com.fintech.beneficiary.infrastructure.adapter.out.kyc;

import com.fintech.beneficiary.application.port.out.LivenessPort;
import org.springframework.stereotype.Component;

/**
 * Prueba de vida sin proveedor: captura y no dictamina.
 *
 * <p>Nunca devuelve «vivo» ni «no vivo». La selfie queda en el expediente y la compara una
 * persona contra la INE; la identidad sigue {@code PENDING} hasta que esa persona firme.
 */
@Component
class StubLivenessAdapter implements LivenessPort {

    @Override
    public Result capture(byte[] selfie, String contentType) {
        return new Result(true, "MANUAL");
    }
}

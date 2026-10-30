package com.fintech.beneficiary.infrastructure.adapter.out.kyc;

import com.fintech.beneficiary.application.port.out.IneReaderPort;
import org.springframework.stereotype.Component;

/**
 * OCR de la INE sin proveedor: no mira las fotos.
 *
 * <p>Devuelve la misma identidad sintética que produce el simulador de KYC a partir del nombre
 * que tecleó el distribuidor, para que la web pueda precargar «Tus datos» y el flujo se pueda
 * recorrer de punta a punta. Las fotos <b>sí</b> se guardan —son la evidencia que mira el
 * analista—; lo que no existe es quien las lea.
 *
 * <p>Que la lectura sea inventada no abre ningún hueco: ella revisa y corrige los datos antes de
 * enviarlos, y la identidad queda {@code PENDING} hasta que una persona compare la INE contra lo
 * capturado.
 */
@Component
class StubIneReader implements IneReaderPort {

    @Override
    public Extraction read(Request request) {
        String[] names = SyntheticIdentity.splitName(request.declaredFullName());
        String curp = SyntheticIdentity.syntheticCurp(names, request.phone());
        return new Extraction(
                names[0], names[1], names[2],
                curp,
                SyntheticIdentity.rfcFrom(curp),
                SyntheticIdentity.DATE_OF_BIRTH,
                SyntheticIdentity.STREET,
                SyntheticIdentity.EXTERIOR_NUMBER,
                SyntheticIdentity.NEIGHBORHOOD,
                SyntheticIdentity.MUNICIPALITY,
                SyntheticIdentity.CITY,
                SyntheticIdentity.STATE,
                SyntheticIdentity.POSTAL_CODE);
    }
}

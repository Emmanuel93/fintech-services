package com.fintech.stp.application.service;

import com.fintech.stp.application.RegisterPaymentOrderCommand;
import com.fintech.stp.domain.StpPaymentOrder;

import java.util.UUID;

/**
 * La cuenta de la que sale el dinero, venga de donde venga.
 *
 * <p>Nació para que el resto del conector no tuviera que saber si la eligió tesorería o si se caía
 * al catálogo local. Retirada la caída (BK-07b), lo que queda es la traducción entre lo que llega en
 * la orden y lo que la firma necesita — que sigue valiendo la pena tener en un solo sitio.
 *
 * @param id id de referencia. Desde BK-07 es el de la cuenta en {@code banking} y <b>no resuelve</b>
 *           contra {@code stp.ordering_accounts} — por eso la orden guarda también los datos. Es lo
 *           que permite recorrer la cadena al revés: de una clave de rastreo a la cuenta propia
 */
record CuentaOrdenante(UUID id, String clabe, String holderName, String taxId,
                       String accountType, String clientNumber) {

    static CuentaOrdenante deLaOrden(RegisterPaymentOrderCommand cmd) {
        return new CuentaOrdenante(cmd.orderingAccountId(), cmd.orderingClabe(), cmd.orderingHolderName(),
                cmd.orderingTaxId(), "40", cmd.orderingClientNumber());
    }

    /**
     * La cuenta con la que se firma, leída de lo que la orden congeló.
     *
     * <p>Antes reconstruía una entidad {@code OrderingAccount} para reusar el código de firma. Con
     * la tabla retirada (BK-07b) esa entidad ya no existe, y fabricarla sólo para pasarla adelante
     * habría dejado en pie la ilusión de que el conector tiene catálogo de cuentas propias.
     */
    static CuentaOrdenante deLaOrdenRegistrada(StpPaymentOrder orden) {
        return new CuentaOrdenante(orden.getOrderingAccountId(), orden.getOrderingClabe(),
                orden.getOrderingHolderName(), orden.getOrderingTaxId(),
                orden.getOrderingAccountType(), orden.getOrderingClientNumber());
    }

    // Nombres al estilo de la entidad que sustituye, para que la firma no tenga que cambiar.
    String getClabe()           { return clabe; }
    String getHolderName()      { return holderName; }
    String getTaxId()           { return taxId; }
    String getAccountType()     { return accountType; }
    String getStpClientNumber() { return clientNumber; }

    /** El tipo de cuenta que espera la cadena original es numérico. */
    Integer accountTypeAsInt() {
        return accountType == null ? null : Integer.valueOf(accountType);
    }
}

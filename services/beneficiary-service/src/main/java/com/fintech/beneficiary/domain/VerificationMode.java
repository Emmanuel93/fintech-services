package com.fintech.beneficiary.domain;

/**
 * Cómo se verifica la beneficiaria.
 *
 * <p>El bundle de diseño tiene <b>una sola opción</b> ({@code kycModes}, L1940-1942): «Ella se
 * verifica sola». Las pantallas 19-21 del export —el KYC asistido, con el distribuidor
 * fotografiando la INE de su clienta— quedaron huérfanas: no están en el riel y {@code beneNext}
 * salta de la 18 a la 22. Son la versión anterior del flujo.
 *
 * <p>El enum existe con un solo valor a propósito. La regla de negocio que lo sostiene está en el
 * aviso ámbar de la pantalla 17: «cada persona a la que le coloques tiene que hacer su propio KYC y
 * autorizar su consulta de buró. <b>Tú no puedes firmar por ella.</b>» Un modo asistido no es una
 * variante de producto pendiente: es exactamente lo que esa regla prohíbe.
 */
public enum VerificationMode {
    /** Le llega una liga a su celular y se verifica desde su propio teléfono, sin el distribuidor. */
    SELF_SERVICE_LINK
}

package com.fintech.collections.domain;

/**
 * Por dónde se contactó. Catálogo cerrado.
 *
 * <p>Era texto libre, y el mismo hecho entraba como «PHONE», «phone» y «Teléfono» según qué cliente
 * lo mandara. El reporte de gestión —que es lo que se le enseña a una revisión— dejaba de poder
 * agruparse justo cuando hacía falta agruparlo.
 */
public enum ContactChannel {
    PHONE, SMS, EMAIL, WHATSAPP, PUSH, VISIT, IVR
}

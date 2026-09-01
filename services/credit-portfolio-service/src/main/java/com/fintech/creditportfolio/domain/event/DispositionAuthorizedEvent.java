package com.fintech.creditportfolio.domain.event;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Una disposición autorizada y <b>pendiente de pago</b>.
 *
 * <p>El hecho que cartera nunca emitía y que {@code disbursement} llevaba escuchando desde el
 * principio: su {@code DispositionAuthorizedListener} existía sin emisor. Mientras el hueco estuvo
 * abierto, la disposición la «pagaba» un stub que devolvía {@code "SPEI-STUB-…"} y la marcaba
 * completada, con lo que contabilidad asentaba una salida de caja de dinero que nunca salió.
 *
 * <p><b>Plano y no anidado, y no es cosmético.</b> El consumidor lo declara plano —igual que el
 * resto de sus payloads y que lo que el conector espera aguas abajo—. Con el beneficiario dentro de
 * un objeto, los tres campos que deciden adónde va el dinero llegaban nulos: disbursement no podía
 * crear la orden y la disposición se quedaba PROCESSING para siempre sin que nadie supiera por qué.
 * Lo cazó una prueba de contrato (BK-44) antes de que llegara a ningún ambiente.
 *
 * <p><b>Lleva {@code sourceCompanyKey} y no un {@code companyId} nulo.</b> Cartera no conoce el
 * catálogo de empresas del orquestador de pagos —ni debe—, pero sí sabe de qué unidad de origen es
 * la cuenta. Publicar la empresa en nulo, como se hacía, hacía que <b>toda</b> disposición muriera
 * con {@code UNRESOLVED_COMPANY} antes de llegar al proveedor: el pago nunca salía y nadie se
 * enteraba, porque el {@code Noop} lo cortocircuitaba antes de llegar ahí.
 *
 * <p><b>No lleva el tipo de disposición.</b> Quien paga no necesita saber si el crédito es de uso
 * propio o de distribuidor — necesita saber a qué cuenta va. Y hacer que el destino dependiera de un
 * tipo que viajaba en un mensaje es lo que permitía desviarlo (BK-13).
 */
public record DispositionAuthorizedEvent(UUID dispositionId,
                                         UUID creditAccountId,
                                         UUID companyId,
                                         String sourceCompanyKey,
                                         BigDecimal amount,
                                         String currency,
                                         String beneficiaryName,
                                         String beneficiaryAccount,
                                         String beneficiaryAccountType,
                                         String beneficiaryTaxId,
                                         String concept) {}

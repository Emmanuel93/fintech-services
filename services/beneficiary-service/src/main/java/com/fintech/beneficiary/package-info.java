/**
 * D13 — Beneficiary [Colocación B2B2C]
 *
 * <p>Orquesta la colocación: un distribuidor con línea revolvente le presta a sus clientes, y cada
 * cliente queda con su propio expediente. <strong>No inventa dominio, lo compone</strong> — el
 * prospecto lo crea origination, el Party y la relación los lleva party, el buró lo consulta
 * scoring, la línea la descuenta wallet y el SPEI lo manda disbursement. Lo suyo es el hilo que los
 * une: el agregado {@code Placement}, la liga de invitación, la constancia de autorización de buró
 * y la evidencia de la decisión del distribuidor.
 *
 * <p><strong>Quién debe:</strong> la distribuidora. Hay una sola cuenta de crédito —su línea
 * {@code DISTRIBUTOR_LINE}— y cada colocación es una {@code Disposition THIRD_PARTY_CREDIT} de esa
 * línea. A la beneficiaria se le informa que el crédito es suyo, y lo es frente a la distribuidora:
 * lo que queda a su nombre es el contrato de colocación, un documento del expediente y no una
 * exposición en cartera.
 *
 * <p><strong>Cuatro reglas que el servicio existe para hacer cumplir:</strong>
 * <ol>
 *   <li><strong>La línea no se aparta al invitar</strong>, se descuenta al aprobar. Por eso
 *       {@code APPROVED} no es terminal: credit-portfolio tiene la última palabra sobre el cupo y
 *       contesta asíncrono.</li>
 *   <li><strong>El buró se consulta con la autorización de la beneficiaria</strong>, nunca con la
 *       del distribuidor — que por eso jamás ve la pantalla de consentimiento de su clienta. La
 *       constancia (fecha, hora, IP, versión del texto) viaja en
 *       {@code beneficiary.bureau-consent-granted}.</li>
 *   <li><strong>Kredius no filtra por score.</strong> El reporte se entrega completo y el
 *       distribuidor decide, firmando que asume el riesgo. La decisión de scoring
 *       ({@code AUTO_APPROVED}/{@code REJECTED}) se ignora deliberadamente.</li>
 *   <li><strong>El KYC es todo o nada</strong>, y no se crea prospecto al invitar: hacerlo
 *       dispararía el prefetch de buró de scoring antes de que ella autorizara nada.</li>
 * </ol>
 *
 * <p><strong>Dos superficies:</strong> la privada del distribuidor, detrás del JWT que valida el
 * gateway; y la pública de la beneficiaria ({@code /api/v1/beneficiary/public/**}), sin JWT y sin
 * cuenta, autorizada por un token de invitado de 15 minutos que sólo abre este servicio.
 *
 * <p>Schema DB: {@code beneficiary}
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"shared"}
)
package com.fintech.beneficiary;

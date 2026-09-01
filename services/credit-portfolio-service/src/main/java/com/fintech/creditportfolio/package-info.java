/**
 * D4★ — Credit Portfolio [Core]
 *
 * <p><strong>El corazón del sistema.</strong> La cuenta de crédito viva: saldos, disposiciones,
 * plan de amortización, envejecimiento y los hechos que el resto de la plataforma consume para
 * saber qué está pasando con el dinero de un cliente.
 *
 * <p><strong>Qué es suyo.</strong> El <em>saldo</em> —cuánto se debe, de qué está compuesto y por
 * qué cambió—. Toda variación entra por un hecho registrado en {@code balance_events}, nunca por
 * una escritura directa: es lo que permite reconstruir un saldo desde cero y explicar cada peso.
 *
 * <p><strong>Qué NO es suyo, y por qué importa.</strong>
 * <ul>
 *   <li><b>No dispersa dinero.</b> Tuvo un {@code SpeiDispatchPort} con una sola implementación —un
 *       stub que devolvía {@code "SPEI-STUB-…"}, marcaba la disposición completada y hacía que
 *       contabilidad asentara una salida de caja de dinero que nunca salió—. Se eliminó (BK-11):
 *       cartera <b>autoriza</b> y publica el hecho; quien paga es {@code disbursement}.</li>
 *   <li><b>No decide el tipo de disposición.</b> Lo decide el <b>producto</b>, desde sus
 *       {@code Capabilities}. Cuando venía en la petición, una solicitud sobre una línea de
 *       distribuidor que mandara {@code "SELF_USE"} acreditaba el dinero a la distribuidora en vez
 *       de a la beneficiaria (BK-13).</li>
 *   <li><b>No calcula intereses.</b> Eso es de {@code charges}. Cartera recibe el cargo aplicado y
 *       lo integra al saldo.</li>
 *   <li><b>No decide cuándo corre el día.</b> Eso es de {@code closing}, que además le devuelve el
 *       exigible del ciclo — sin eso, una revolvente pura no tendría nada que vencer.</li>
 * </ul>
 *
 * <p><strong>La regla que ordena las disposiciones.</strong> Una revolvente no tiene plazo; lo tiene
 * cada disposición. Pero <em>cuándo</em> se fija ese plazo depende del producto: un distribuidor lo
 * decide <b>al colocar</b> ({@code AT_DISPOSITION}); una tarjeta nace revolvente pura y su titular
 * la difiere <b>después</b> ({@code POST_HOC}). Tratar las dos igual hacía que una compra con
 * tarjeta naciera ya parcializada al plazo por defecto del producto.
 *
 * <p>Schema DB: {@code credit_portfolio} · Puerto: {@code 8087}
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {"shared"})
package com.fintech.creditportfolio;

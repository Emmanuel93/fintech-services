/**
 * D14 — Closing &amp; Cutoff [Transversal]
 *
 * <p>Dueño del <strong>calendario de cierre</strong> de la plataforma: qué cierra, cuándo, con qué
 * política y sobre qué unidades. No calcula intereses, no decide mora y no asienta pólizas —
 * <em>orquesta y reparte</em>; los dominios ejecutan lo suyo y responden.
 *
 * <p><strong>Por qué es un servicio y no un módulo de cartera.</strong> El cierre cambia cuando
 * cambia el calendario contable, la regulación o la política de corte; cartera cambia cuando cambia
 * el producto crediticio. Son ejes independientes. Y meter el barrido de todas las cuentas dentro
 * del JVM que sirve la API de cartera es exactamente lo que hoy impide escalar.
 *
 * <p><strong>El calendario de corte es de este servicio, no de cartera.</strong> Aunque para un
 * producto no revolvente la cadencia coincida con el vencimiento de la cuota, el cierre
 * <em>deriva y persiste su propio calendario</em> en {@code cutoff_schedules} a partir de la
 * política del producto y de lo que aprende por evento. Leer {@code installments.due_date} de
 * cartera en línea rompería el aislamiento y dejaría el calendario sin dueño.
 *
 * <p><strong>Aislamiento.</strong> Consumidor propio, base propia, cero escrituras cruzadas y cero
 * REST síncrono durante la ventana de cierre. Si el cierre se atrasa, el lag es suyo.
 *
 * <p>Schema DB: {@code closing}
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {"shared"})
package com.fintech.closing;

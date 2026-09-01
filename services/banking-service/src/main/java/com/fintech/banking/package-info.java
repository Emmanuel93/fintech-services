/**
 * D15 — Banking [Supporting]
 *
 * <p>El dominio de <strong>tesorería</strong>: nuestras cuentas en instituciones financieras, de cuál
 * sale cada pago, por qué rail y con qué proveedor, y qué dice el banco que pasó.
 *
 * <p><strong>Por qué es un servicio y no un módulo de disbursement.</strong> Hasta ahora la CLABE de
 * la que sale el dinero vivía dentro del <em>conector</em> de STP ({@code stp.ordering_accounts}) y
 * se elegía con un {@code is_default} por empresa, ciego al saldo y al costo. Eso convierte una
 * decisión de tesorería en un detalle de cómo se firma una cadena original, y obliga a que cada
 * proveedor nuevo traiga su propia copia del catálogo de cuentas propias.
 *
 * <p>La frontera con sus vecinos, en una línea cada una:
 * <ul>
 *   <li><b>banking</b> — de qué cuenta nuestra sale, por dónde, y qué reporta el banco.</li>
 *   <li><b>disbursement</b> — qué hay que pagar, a quién, y que no se pague dos veces.</li>
 *   <li><b>stp</b> — cómo se le habla a STP.</li>
 * </ul>
 *
 * <p><strong>No conoce el dominio de crédito.</strong> No sabe qué es una cuenta de crédito ni una
 * disposición: recibe una petición de pago con su referencia opaca y responde por dónde sale.
 *
 * <p><strong>El dinero siempre sale por una cuenta bancaria.</strong> No hay camino que confirme un
 * pago sin haberlo intentado: en ambientes bajos el proveedor es un mock con escenarios, nunca un
 * no-op que devuelva un folio inventado.
 *
 * <p>Schema DB: {@code banking} · Puerto: {@code 8104}
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {"shared"})
package com.fintech.banking;

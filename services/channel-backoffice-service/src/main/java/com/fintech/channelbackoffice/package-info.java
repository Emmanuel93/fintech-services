/**
 * BFF del backoffice — canal {@code BACKOFFICE}.
 *
 * <p>Agrega y orquesta llamadas a los servicios de dominio para la consola operativa. Es stateless:
 * no tiene base de datos y no es fuente de verdad de nada. Redis solo guarda caché de agregados
 * caros (el resumen del dashboard), nunca estado de negocio.
 *
 * <p>Cuatro responsabilidades y ninguna más:
 * <ol>
 *   <li><b>Agregación</b> — un resumen que en el dominio son N llamadas.</li>
 *   <li><b>Composición del expediente</b> — el 360° del cliente cruza ~10 servicios.</li>
 *   <li><b>Alcance de datos por rol</b> — un EXECUTIVE solo ve su cartera, y eso se resuelve
 *       aquí desde el token, nunca con un filtro que mande el navegador.</li>
 *   <li><b>Passthrough administrado</b> — CRUD que ya existe en el dominio, con el rol validado.</li>
 * </ol>
 *
 * <p>Lo que <em>no</em> hace: persistir negocio, calcular saldos, ni compensar con bucles la falta
 * de listados en el dominio. Si una pantalla necesita listar, el listado se abre en el servicio
 * dueño del dato.
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"shared"}
)
package com.fintech.channelbackoffice;

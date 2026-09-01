/**
 * D10 — Sales Org [Supporting]
 *
 * <p><strong>La estructura comercial</strong>: quién le reporta a quién, y hasta dónde alcanza la
 * vista de cada quien. Niveles configurables —de NACIONAL a DISTRIBUIDOR— sin que el número de
 * niveles esté escrito en el código.
 *
 * <p><strong>Por qué el camino es un {@code LTREE} y no una tabla de padres.</strong> La pregunta
 * que este servicio contesta todo el tiempo es «dame todo lo que cuelga de aquí», y con una columna
 * {@code parent_id} eso es una consulta recursiva por cada tablero que alguien abre. El camino
 * materializado la convierte en un prefijo indexado.
 *
 * <p><strong>No conoce el crédito.</strong> Resuelve jerarquía y alcance; qué se hace con ese
 * alcance —filtrar una cartera, atribuir una comisión— es de quien pregunta.
 *
 * <p>Schema DB: {@code sales_org} · Puerto: {@code 8100}
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {"shared"})
package com.fintech.salesorg;

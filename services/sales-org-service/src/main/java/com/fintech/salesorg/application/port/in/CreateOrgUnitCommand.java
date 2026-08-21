package com.fintech.salesorg.application.port.in;

import java.util.UUID;

/**
 * Alta de una unidad del árbol comercial.
 *
 * @param levelId      nivel de la unidad (define su profundidad esperada)
 * @param parentUnitId unidad padre; {@code null} solo para una unidad raíz (nivel de profundidad 0)
 * @param code         código de la unidad; etiqueta LTREE válida y único
 * @param name         nombre legible
 * @param partyRef     persona del nodo: staffUserId (EXECUTIVE) o partyId del distribuidor
 *                     (DISTRIBUTOR); {@code null} en unidades estructurales
 * @param createdBy    id del empleado que la da de alta (atribución)
 */
public record CreateOrgUnitCommand(
        UUID levelId,
        UUID parentUnitId,
        String code,
        String name,
        UUID partyRef,
        String createdBy) {}

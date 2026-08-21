package com.fintech.creditportfolio.application.port.out;

import java.math.BigDecimal;

/**
 * Cartera agregada por <b>unidad de origen</b>: la que colocó el crédito, sellada al activarlo.
 *
 * <p>No es lo mismo que «la unidad del ejecutivo que lleva hoy al cliente». La cartera se reasigna
 * y {@code origin_unit_code} no se recalcula nunca —esa fue la decisión del changeset 013, para que
 * la balanza de marzo siga dando lo mismo en agosto—, así que las dos atribuciones responden
 * preguntas distintas y pueden dar cifras distintas de la misma cartera. Por eso este agregado
 * viaja con nombre propio y no mezclado con el rollup comercial.
 *
 * <p>Se devuelven los tres tramos IFRS-9 en vez de una provisión ya calculada: la escala de pérdida
 * esperada vive en el canal, junto al resto del tablero, y duplicarla aquí garantizaría que un día
 * las dos se separen.
 */
public record OriginUnitStat(
        String unitCode,
        long accounts,
        long delinquentAccounts,
        BigDecimal principal,
        /** Al corriente y hasta 30 días. */
        BigDecimal stage1Principal,
        /** Atraso temprano 31–90 (SICR). NO es cartera vencida. */
        BigDecimal stage2Principal,
        /** Cartera vencida oficial CNBV/IFRS-9: 90+. */
        BigDecimal stage3Principal
) {}

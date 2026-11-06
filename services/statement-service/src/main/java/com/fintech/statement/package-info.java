/**
 * D19 — Estados de cuenta.
 *
 * <p>Un estado de cuenta por cada corte sellado: escucha {@code closing.cutoff-closed}, lee la vista
 * del período en cartera, elige la plantilla por tipo de producto (plazo fijo, revolvente,
 * distribuidora), la llena con JasperReports, guarda el PDF y publica
 * {@code statement.statement-generated}.
 *
 * <p><b>Escala por particiones.</b> Cada corte es un mensaje con la cuenta como llave; cada pod del
 * grupo genera los de sus particiones. No hay coordinador: la idempotencia la da la llave natural
 * (cuenta, ciclo) y un reclamo atómico en la base.
 *
 * <p>Schema DB: {@code statement}
 */
package com.fintech.statement;

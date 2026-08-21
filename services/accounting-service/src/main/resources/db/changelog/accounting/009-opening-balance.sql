--liquibase formatted sql
--changeset accounting:009-opening-balance author:system
--comment Asiento de apertura: para cuando contabilidad conoce un crédito a media vida.

-- **El defecto que esto corrige.** El shadow de saldos arranca en cero, así que el monto de cada
-- asiento sale del delta contra cero la primera vez que se ve una cuenta. Si contabilidad no
-- presenció el desembolso —porque la cuenta ya existía cuando el servicio empezó a consumir, o
-- porque se reprocesó el tópico desde un offset posterior— el PRIMER evento que sí ve absorbe el
-- saldo completo y queda etiquetado con ese hecho.
--
-- El síntoma medido: un préstamo de $20,000 con 1% de comisión de apertura generó una póliza de
-- comisión de $20,200. Cuadraba —cargos = abonos— y decía que la institución ganó medio millón en
-- comisiones cuando ganó cinco mil, con la cartera colocada en cero. Cuadrar no es ser cierto.
--
-- La contrapartida es una cuenta de capital, no un ingreso: incorporar un saldo que ya existía no es
-- ganar dinero. Es exactamente lo que un contador llama asiento de apertura.
INSERT INTO accounting.ledger_accounts (code, name, type) VALUES
    ('3901', 'Saldo inicial por incorporación de cartera', 'EQUITY');

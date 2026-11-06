package com.fintech.statement.domain;

/**
 * {@code PENDING} recién recibido el corte · {@code GENERATING} un pod lo tiene ·
 * {@code GENERATED} con su PDF · {@code FAILED} con la causa, reintentable.
 */
public enum StatementStatus { PENDING, GENERATING, GENERATED, FAILED }

package com.fintech.identity.domain;

/**
 * Relación laboral del empleado. Distingue al personal de nómina del colaborador de un distribuidor
 * B2B2C, que entra al backoffice con alcance acotado a la cartera que originó.
 */
public enum EmployeeType {

    /** Personal de nómina de la institución. */
    INTERNO,

    /** Colaborador de un distribuidor/aliado comercial con acceso acotado. */
    COLABORADOR_EMPRESARIAL
}

package com.fintech.identity.domain;

/**
 * Roles del personal interno. Son la fuente de verdad del RBAC del backoffice: viajan en el claim
 * {@code roles} del JWT, el gateway los propaga como {@code X-Roles} y el BFF decide con ellos qué
 * módulos y qué acciones expone.
 */
public enum StaffRole {

    /** Acceso total, incluida la administración de staff. */
    ADMIN,

    /** Supervisa la operación diaria y la cartera de su equipo. */
    OPS_SUPERVISOR,

    /** Analiza solicitudes de crédito y expedientes de cliente. */
    CREDIT_ANALYST,

    /** Decide sobre solicitudes en revisión manual. */
    UNDERWRITER,

    /** Miembro del comité de crédito — decide sobre montos que exceden la facultad individual. */
    COMMITTEE,

    /** Ejecutivo de cartera — atiende a los clientes que tiene asignados. */
    EXECUTIVE,

    /**
     * Gerente comercial — dirige una rama de la estructura de ventas.
     *
     * <p>Uno solo para los cuatro escalones (sucursal, zona, región, nacional): hasta dónde llega
     * lo dice la unidad a la que está adscrito, no el rol. Un rol por nivel obligaría a repetir
     * cada regla cuatro veces y a rehacerlas al abrir un escalón nuevo.
     */
    COMMERCIAL_MANAGER,

    /** Gestor de cobranza — atiende casos en mora. */
    COLLECTIONS_AGENT,

    /** Analiza riesgo de cartera, etapas IFRS-9 y provisiones. */
    RISK_ANALYST,

    /** Administra el catálogo de productos de crédito. */
    PRODUCT_MANAGER,

    /** Finanzas — comisiones, liquidaciones, contabilidad y facturación. */
    FINANCE,

    /** Marketing — campañas y políticas de notificación. */
    MARKETING,

    /** Auditor — acceso de solo lectura a la bitácora y a los expedientes. */
    AUDITOR,

    /** Soporte — consulta de clientes para atención, sin facultades de decisión. */
    SUPPORT;

    /**
     * Roles con facultad de decisión sobre dinero o sobre el expediente de un cliente. MFA es
     * obligatorio para ellos (Fase 5); hoy solo se usa para marcarlos en el directorio.
     */
    public boolean requiresStrongAuthentication() {
        return this == ADMIN
                || this == UNDERWRITER
                || this == COMMITTEE
                || this == FINANCE
                || this == OPS_SUPERVISOR;
    }
}

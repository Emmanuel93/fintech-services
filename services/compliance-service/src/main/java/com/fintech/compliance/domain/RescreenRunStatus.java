package com.fintech.compliance.domain;

public enum RescreenRunStatus {
    /** Corriendo. Los veredictos vigentes siguen valiendo — es la ventana que el negocio asumió. */
    RUNNING,
    /** Miró a toda la población. */
    COMPLETED,
    /**
     * Se interrumpió. Lo que alcanzó a actualizar queda actualizado —es mejor que lo anterior— pero
     * la corrida no cuenta: no se sabe a quién no se llegó, y la siguiente vuelve a mirar a todos.
     */
    FAILED
}

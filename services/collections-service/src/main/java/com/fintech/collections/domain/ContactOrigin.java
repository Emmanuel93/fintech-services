package com.fintech.collections.domain;

/**
 * Quién originó el contacto.
 *
 * <p>La distinción decide dos cosas distintas y por eso no basta con mirar si hay {@code agentId}:
 *
 * <ul>
 *   <li><b>El tope diario CONDUSEF sólo cuenta los MANUAL.</b> Si la cadencia automática consumiera
 *       el cupo, el agente llegaría a un caso sin intentos disponibles por mensajes que él no
 *       mandó. Ver la decisión regulatoria documentada en la estrategia de cobranza.</li>
 *   <li><b>Un AUTOMATIC no tiene agente a quien atribuirle el acto.</b> Inventar un usuario
 *       «SISTEMA» para llenar la columna haría que la bitácora dijera que alguien llamó.</li>
 * </ul>
 */
public enum ContactOrigin {
    MANUAL, AUTOMATIC
}

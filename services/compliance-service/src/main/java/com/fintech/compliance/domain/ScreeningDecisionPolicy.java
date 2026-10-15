package com.fintech.compliance.domain;

/**
 * Qué hace cada tipo de lista cuando hay coincidencia.
 *
 * <p>No todas las listas significan lo mismo, y meterlas en un saco llamado «lista negra» pierde la
 * distinción que decide qué se hace con un match.
 */
public final class ScreeningDecisionPolicy {

    private ScreeningDecisionPolicy() { }

    /**
     * Si una coincidencia con esta lista <b>detiene</b> a la persona.
     *
     * <table>
     *   <tr><th>Lista</th><th>Decisión</th><th>Quién la tomó</th></tr>
     *   <tr><td>{@code SANCTIONS}</td><td>bloquea</td><td>no hay nada que decidir: es una prohibición de operar</td></tr>
     *   <tr><td>{@code PEP}</td><td><b>bloquea</b></td><td>decisión del negocio (2026-09-14)</td></tr>
     *   <tr><td>{@code INTERNAL}</td><td>bloquea</td><td>lista propia: fraude comprobado, quebranto doloso</td></tr>
     *   <tr><td>{@code TAX_BLACKLIST}</td><td><b>NO bloquea</b></td><td>pendiente de decidir</td></tr>
     * </table>
     *
     * <p><b>Sobre el PEP.</b> La práctica habitual de cumplimiento es diligencia reforzada —más
     * evidencia y aprobación de más alto nivel— y no bloqueo, porque bloquear equivale a negar
     * servicio a un funcionario público por serlo. Se planteó y <b>el negocio decidió bloquear</b>.
     * Queda escrito aquí para que quien lo lea sepa que fue una decisión y no un descuido, y para
     * que cambiarla sea editar esta línea.
     *
     * <p><b>Sobre la lista fiscal (SAT 69-B).</b> Queda <b>pendiente</b> de decisión y por eso hoy
     * no bloquea: se registra la coincidencia y se deja visible, pero no detiene la identidad. Es un
     * hecho fiscal —un contribuyente con operaciones presuntamente inexistentes— y no dice nada
     * sobre si la persona es quien dice ser. Mientras no se decida, bloquear sería aplicar un
     * criterio que nadie aprobó.
     */
    public static boolean blocks(WatchlistKind kind) {
        return switch (kind) {
            case SANCTIONS, PEP, INTERNAL -> true;
            case TAX_BLACKLIST            -> false;
        };
    }
}

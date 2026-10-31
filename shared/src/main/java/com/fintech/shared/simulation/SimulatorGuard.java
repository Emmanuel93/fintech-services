package com.fintech.shared.simulation;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

/**
 * La guarda de arranque de todo simulador de proveedor: fuera de un ambiente bajo, no arranca.
 *
 * <h2>Por qué una guarda de arranque y no un aviso</h2>
 *
 * Un simulador activo en producción es la peor clase de incidente: todo se ve verde. Con el stub de
 * STP no sale un peso; con las listas de gobierno simuladas, el AML cruza a cada persona contra
 * nombres inventados; con la firma simulada, cualquier cosa cuenta como firma. Un {@code WARN} en
 * el log no detiene nada de eso. Que el servicio no arranque, sí.
 *
 * <h2>Cómo decide: lista blanca, declarada</h2>
 *
 * Se permite si el ambiente se declara bajo —{@code fintech.environment} (variable
 * {@code FINTECH_ENVIRONMENT}) en {@value #NON_PRODUCTION_LIST}— o si todos los perfiles activos lo
 * son (así corren las pruebas, con el perfil {@code test}). Cualquier otra cosa, o nada, impide el
 * arranque: es el fallo seguro.
 *
 * <p><b>{@code docker} ya no cuenta como ambiente bajo.</b> Describe dónde corre el proceso, no qué
 * ambiente es: el compose de este repo arranca con ese perfil, y promoverlo a producción sin tocar
 * nada habría dejado los simuladores encendidos. El compose declara {@code FINTECH_ENVIRONMENT=local}
 * explícitamente; un despliegue real no lo hará.
 *
 * <p>Una declaración de producción gana siempre: {@code fintech.environment=production} o un perfil
 * {@code prod} impiden el arranque aunque convivan con un perfil bajo.
 */
public final class SimulatorGuard {

    private static final Logger log = LoggerFactory.getLogger(SimulatorGuard.class);

    static final String NON_PRODUCTION_LIST = "local, dev, test, ci, qa, staging";
    static final Set<String> NON_PRODUCTION = Set.of("local", "dev", "test", "ci", "qa", "staging");
    static final Set<String> PRODUCTION = Set.of("prod", "production", "prd", "produccion");

    private SimulatorGuard() {}

    /**
     * Impide el arranque del simulador fuera de un ambiente bajo; si se permite, lo deja dicho.
     *
     * @param simulator qué simulador es y qué sustituye, para el mensaje («StubStpGateway (STP/SPEI)»)
     * @param howToDisable cómo se enciende el adaptador real, para quien lea el error
     */
    public static void requireNonProduction(Environment environment, String simulator, String howToDisable) {
        String declared = environment.getProperty("fintech.environment", "").trim().toLowerCase(Locale.ROOT);
        String[] profiles = environment.getActiveProfiles();
        if (!isAllowed(declared, profiles)) {
            throw new IllegalStateException(simulator + " es un simulador y no arranca en este ambiente"
                    + " (fintech.environment='" + declared + "', perfiles=" + Arrays.toString(profiles) + ")."
                    + " Sólo se permite con FINTECH_ENVIRONMENT en [" + NON_PRODUCTION_LIST + "]."
                    + " Para el proveedor real: " + howToDisable);
        }
        log.warn("SIMULADOR ACTIVO: {} (fintech.environment='{}', perfiles={}) — no usar en producción",
                simulator, declared, Arrays.toString(profiles));
    }

    static boolean isAllowed(String declared, String[] profiles) {
        String env = declared == null ? "" : declared.trim().toLowerCase(Locale.ROOT);
        boolean productionDeclared = PRODUCTION.contains(env) || Arrays.stream(profiles)
                .map(p -> p.toLowerCase(Locale.ROOT))
                .anyMatch(PRODUCTION::contains);
        if (productionDeclared) {
            return false;
        }
        if (NON_PRODUCTION.contains(env)) {
            return true;
        }
        return profiles.length > 0 && Arrays.stream(profiles)
                .map(p -> p.toLowerCase(Locale.ROOT))
                .allMatch(NON_PRODUCTION::contains);
    }
}

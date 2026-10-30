package com.fintech.compliance.application.port.out;

import java.util.Optional;
import java.util.UUID;

/**
 * Qué Party le tocó a un prospecto.
 *
 * <h2>Por qué compliance tiene que preguntarlo</h2>
 *
 * La evaluación nace de {@code origination.prospect-created}, el mismo evento del que {@code party}
 * crea el Party — así que, al evaluar, el Party casi nunca existe todavía y el veredicto sale con
 * {@code partyId} nulo. Eso no era un detalle: credit-portfolio proyecta el veredicto <b>por
 * partyId</b> e ignora los que no lo traen, así que la persona se quedaba sin veredicto en la
 * compuerta de disposición para siempre — ni el dictamen de un analista lo arreglaba, porque se
 * republicaba también sin partyId.
 *
 * <p>No hay un evento de «Party creado» al que suscribirse, y crearlo sólo para esto movería la
 * pregunta a otro servicio sin resolverla. Preguntar es lo directo: se hace al publicar, cuando
 * hace falta, y si party no contesta se vuelve a intentar en la siguiente publicación.
 */
public interface PartyDirectoryPort {

    /** El Party del prospecto, o vacío si aún no existe o party no respondió. Nunca lanza. */
    Optional<UUID> partyIdOf(UUID prospectId);
}

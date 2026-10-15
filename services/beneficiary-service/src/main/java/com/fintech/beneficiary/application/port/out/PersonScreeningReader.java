package com.fintech.beneficiary.application.port.out;

import com.fintech.beneficiary.domain.ScreeningVerdict;

import java.util.UUID;

/**
 * El veredicto de listas de una persona, para poder aprobar —o no— su colocación.
 *
 * <p>Lo produce {@code compliance} (D16) y lo guarda {@code party} en la ficha de la persona. Se lee
 * de ahí y no de D16 directamente porque {@code party} es el dueño de la persona y es a quien ya
 * consulta este servicio: añadir una llamada más a un tercero en el camino de la aprobación sería un
 * punto de fallo extra sobre la misma información.
 */
public interface PersonScreeningReader {

    /**
     * <b>Nunca devuelve vacío.</b> Si no se puede consultar, devuelve un veredicto que
     * <b>bloquea</b>: un {@code Optional} invitaría a escribir {@code .map(...).orElse(true)}, que
     * es fallar abierto redactado de una forma que parece cuidadosa.
     *
     * @param beneficiaryProspectId el prospecto de la beneficiaria — es la llave que comparten
     *                              {@code compliance}, {@code party} y esta colocación
     */
    ScreeningVerdict screeningOf(UUID beneficiaryProspectId);
}

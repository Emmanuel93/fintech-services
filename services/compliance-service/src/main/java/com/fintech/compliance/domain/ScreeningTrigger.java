package com.fintech.compliance.domain;

/**
 * Por qué se consultó a esta persona contra las listas.
 *
 * <p>Se guarda en cada consulta porque <b>cambia lo que la consulta prueba</b>. Una del alta
 * demuestra diligencia al dar entrada a alguien; una del re-cruce demuestra vigilancia continua;
 * una bajo demanda demuestra que alguien preguntó — y quién pregunta y cuándo es justo lo que una
 * revisión quiere ver.
 */
public enum ScreeningTrigger {

    /** Al dar de alta a la persona, sea titular de uso propio, distribuidor o beneficiaria. */
    ONBOARDING,

    /** Al activarse una lista nueva: la población viva se vuelve a cruzar. */
    RESCREEN,

    /**
     * Alguien la pidió — remediación de cartera histórica, revisión de un caso.
     *
     * <p>Es la que menos ocurre y la que más explica: si una persona tiene tres consultas bajo
     * demanda en una semana, alguien estaba mirándola.
     */
    ON_DEMAND
}

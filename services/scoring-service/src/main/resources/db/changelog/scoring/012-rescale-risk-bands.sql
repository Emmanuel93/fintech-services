-- Reescalar las bandas de riesgo al motor ampliado.
--
-- El motor pasó de cinco variables a una veintena —ingreso, antigüedad laboral, edad, utilización,
-- dependientes— y con ello el puntaje alcanzable casi se duplicó. Las bandas se quedaron donde
-- estaban: BAJO ≥200, MEDIO ≥100.
--
-- El efecto no era sobre un caso raro, era sobre todos: cada perfil sube unos 200 puntos por las
-- reglas nuevas, así que la clasificación entera se corrió hacia la aprobación. Medido contra el
-- flujo de integración, que es donde está escrito el comportamiento esperado:
--
--     FICO 760 + 1 TC        antes 430 → BAJO  (auto)      ahora 630
--     FICO 650 + 1 TC        antes 130 → MEDIO (mesa)      ahora ~330 → BAJO (auto)
--
-- Es decir: un perfil de banda media empezó a aprobarse solo. No porque alguien decidiera que ese
-- riesgo es aceptable, sino porque la escala se movió debajo de los umbrales sin que nadie los
-- moviera con ella. Un motor más informado debería discriminar mejor, no aprobar más.
--
-- Se desplazan las dos bandas los mismos +200 con que se movió la escala, que es lo que conserva la
-- severidad anterior: 630 sigue auto-aprobado, 330 vuelve a la mesa de análisis.
--
-- Sólo se tocan las bandas estándar (200/100). Las políticas que fijan un BAJO inalcanzable a
-- propósito —para mandar todo su segmento a revisión manual— se quedan como están: ahí el número
-- no es una escala, es una decisión.

-- changeset scoring-service:012-rescale-risk-bands
UPDATE scoring.risk_thresholds
   SET min_score = 400
 WHERE risk_level = 'BAJO'
   AND min_score = 200;

UPDATE scoring.risk_thresholds
   SET min_score = 300
 WHERE risk_level = 'MEDIO'
   AND min_score = 100;

--liquibase formatted sql
--changeset banking:008-movement-trace-steps
--comment La traza dice CUÁNDO pasó cada tramo, no sólo que pasó.

-- La traza guardaba que un tramo existía —el id de la cuenta, la clave, la línea— pero no cuándo
-- llegó. Para una investigación es la mitad de la respuesta: «salió a las 07:22 y el banco lo
-- liquidó a las 07:23» dice dónde buscar; «tiene clave de rastreo» no.
--
-- `settled_at` es la liquidación que confirma el conector (stp.order-settled). No es un eslabón de
-- la cadena —la confirmación independiente es el estado de cuenta (`line_id`)— y por eso no cuenta
-- para `complete`: es lo que permite distinguir «el banco aún no lo reporta» de «aún no llega».
ALTER TABLE banking.movement_trace ADD COLUMN routed_at     TIMESTAMPTZ;
ALTER TABLE banking.movement_trace ADD COLUMN dispatched_at TIMESTAMPTZ;
ALTER TABLE banking.movement_trace ADD COLUMN settled_at    TIMESTAMPTZ;
ALTER TABLE banking.movement_trace ADD COLUMN reconciled_at TIMESTAMPTZ;
ALTER TABLE banking.movement_trace ADD COLUMN posted_at     TIMESTAMPTZ;


-- Bloqueo optimista. Cada tramo llega por un topic distinto y dos pueden tocar la misma traza en el
-- mismo instante —el ruteo y la aceptación del mismo pago—. Sin versión, el que confirmaba segundo
-- reescribía la fila con lo que leyó antes y borraba el tramo del otro: una cuenta de salida que
-- desaparece, o un estado que vuelve a ROUTED con la clave ya puesta.
ALTER TABLE banking.movement_trace ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

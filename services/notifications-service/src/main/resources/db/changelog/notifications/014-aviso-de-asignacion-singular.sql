--liquibase formatted sql

--changeset notifications:aviso-de-asignacion-singular
--comment El aviso de asignación describe lo que de verdad ocurre: un cliente, no un lote.

-- La plantilla decía «Ahora llevas {{cantidad}} cliente(s) más», escrita para una asignación
-- **masiva que no existe**: `POST /clients/{partyId}/assign-executive` asigna de a uno y no hay
-- operación de lote en ningún sitio. Con el hecho real, `cantidad` sería siempre 1 —«Ahora llevas
-- 1 cliente(s) más»— y cargar cuarenta clientes daría cuarenta campanas iguales.
--
-- Un aviso que describe una operación que nadie puede hacer no es un aviso incompleto: es uno que
-- va a leerse mal siempre. Se ajusta al hecho que sí ocurre.
--
-- Si algún día existe la asignación masiva, emitirá su propio hecho con su propia clave y su
-- propia plantilla — que es donde `{{cantidad}}` sí significa algo.
UPDATE notifications.notification_templates
   SET subject = 'Se te asignó un cliente',
       body    = '{{cliente}} es ahora parte de tu cartera. Revisa su expediente para ponerte al día.'
 WHERE event_key = 'STAFF_CLIENTES_ASIGNADOS';

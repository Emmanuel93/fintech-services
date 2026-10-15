-- Los placeholders son identificadores de código: los escribe `NotificationTriggerService` y los
-- lee `NotificationTemplate.render`. El texto de la plantilla sigue en español —es lo que lee el
-- cliente— pero el nombre de la variable no es texto, es contrato entre las dos piezas.
--
-- Estaban mezclados: `{{installmentNumber}}` y `{{totalAmount}}` en inglés junto a `{{nombre}}` y
-- `{{cuotasRestantes}}` en español, en la misma frase. Se unifican.
--
-- Importa que vaya junto al rename en Java: `render` deja el placeholder crudo si la variable falta,
-- así que renombrar sólo un lado no falla — manda el mensaje con «{{nombre}}» literal al cliente.

-- changeset notifications-service:015-rename-template-placeholders
UPDATE notifications.notification_templates
   SET body    = REPLACE(REPLACE(body,    '{{nombre}}', '{{firstName}}'),
                         '{{cuotasRestantes}}', '{{remainingInstallments}}'),
       subject = REPLACE(REPLACE(subject, '{{nombre}}', '{{firstName}}'),
                         '{{cuotasRestantes}}', '{{remainingInstallments}}')
 WHERE body    LIKE '%{{nombre}}%' OR body    LIKE '%{{cuotasRestantes}}%'
    OR subject LIKE '%{{nombre}}%' OR subject LIKE '%{{cuotasRestantes}}%';

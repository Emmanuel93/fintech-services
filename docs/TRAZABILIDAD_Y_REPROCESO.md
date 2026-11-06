# Trazabilidad y reproceso

Cada flujo de negocio tiene un **correlation-id**: desde la petición o el primer mensaje que lo inicia
hasta la última póliza que causa. Un mensaje que un servicio no puede procesar no se pierde: queda en el
**registro de mensajes fallidos**, se encuentra por ese id y se puede reprocesar.

Todo vive en `shared` y se activa solo en cada servicio (auto-configuración). Ningún servicio tiene que
hacer nada para tenerlo.

## El correlation-id

| Dónde | Cómo viaja | Quién lo pone |
|---|---|---|
| HTTP de entrada | header `X-Correlation-Id`; si no llega o no es válido, se genera uno | `CorrelationIdFilter` |
| HTTP de salida | el mismo header, en los clientes construidos con los builders de Spring Boot (`RestClient`, `RestTemplate`, `WebClient`) | `CorrelationAutoConfiguration` |
| Kafka de salida | header `x-correlation-id`; si se publica fuera de un flujo (un cron), se genera uno | `CorrelationProducerInterceptor` |
| Kafka de entrada | se lee del mensaje y se pone en el log antes de que corra el listener | `CorrelationRecordInterceptor` |
| Logs | campo `correlationId` del JSON (y `kafkaTopic` mientras se procesa un mensaje) | `logback-base.xml` |

Un id válido tiene hasta 100 caracteres `[A-Za-z0-9._:-]`: un header con saltos de línea o espacios se
descarta y se genera otro, para que nadie pueda falsificar una línea de log.

Los interceptores de Kafka se instalan en **todas** las fábricas de listeners y en todos los
`KafkaTemplate`, incluidas las que cada servicio arma a mano. Un cliente HTTP armado con
`WebClient.builder()` estático no pasa por el builder de Boot y tiene que poner el header él mismo.

## Cuando un mensaje falla

1. El listener lanza una excepción. Se reintenta con espera creciente: 1 s, 2 s, 4 s, 8 s y 16 s
   (`fintech.kafka.failures.retries` y `fintech.kafka.failures.initial-interval-ms`). Durante los
   reintentos la partición espera: los mensajes posteriores del mismo crédito no se adelantan.
2. Si se agotan, el mensaje va a `<tópico>.DLT` tal como llegó (llave, contenido y headers, entre ellos su
   correlation-id), más dónde falló (tópico, partición, offset, grupo, `x-failed-service`) y por qué (la
   excepción).
3. audit-service consume todas las `.DLT` (y las `.dlt` de disbursement y STP) y las guarda en
   `audit.failed_messages`.

Una fábrica que fija su propio manejador de errores lo conserva; si lo arma con
`DeadLetters.errorHandler(backOff)` termina igual en la `.DLT`. Así lo hacen cargos, cierre, cartera y
estados de cuenta, que esperan más a que otro servicio se ponga al día.

## Consultar y reprocesar

| | |
|---|---|
| `GET /api/v1/audit/failures?correlationId=…&topic=…&status=PENDING` | lo que falló en un flujo, en un tópico o sin resolver |
| `GET /api/v1/audit/failures/{id}` | el mensaje, su contenido y la causa |
| `POST /api/v1/audit/failures/{id}/replay` `{"note": "…"}` | lo reenvía a su tópico original con su llave, contenido y headers, más `x-replay-of` |
| `POST /api/v1/audit/failures/{id}/discard` `{"note": "…"}` | lo descarta; pide el motivo |

Consultar es de `AUDITOR`, `REGULATOR`, `ADMIN` y `OPS`. Reprocesar y descartar, de `ADMIN` y `OPS`, y
queda quién lo hizo y por qué. Reprocesar es seguro porque cada consumidor es idempotente por la llave del
hecho: un mensaje que sí llegó a aplicarse no se aplica dos veces.

## Lo que falta

- **Outbox**: casi todos los servicios publican dentro de la transacción con envío asíncrono; si el
  broker no responde después del commit, el hecho se guarda y el evento no sale. Spring Modulith ya tiene
  sus tablas en 20 servicios; falta publicar a través de su registro los hechos que cambian estado.
  Publicar dentro de la transacción tiene además una carrera cuando otro servicio contesta por id: la
  respuesta puede llegar antes del commit y no encontrar la fila. payments (orden ↔ `balance-updated`),
  origination (solicitud ↔ `scoring-completed`), disbursement y stp ya publican tras el commit
  (`TransactionSynchronization`); los demás publicadores aún no.
- **Gateway**: el gateway reactivo todavía no genera el id; hoy lo genera el primer servicio que recibe
  la petición.
- **Pantalla**: el registro todavía no se ve en el backoffice.

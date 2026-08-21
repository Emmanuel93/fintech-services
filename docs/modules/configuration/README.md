# T5 — Configuration

**Estado:** ⬜ Pendiente (Módulo 2)  
**Tipo:** Transversal  
**Schema DB:** `configuration`  
**Paquete Java:** `com.fintech.configuration`

## Responsabilidad

Parámetros de negocio versionados con ciclo maker-checker. Todos los módulos hacen pull de parámetros en startup y los cachean. Invalida caches al publicar `ConfigurationUpdated`.

## Eventos publicados

| Evento | Tópico | Consumidores |
|---|---|---|
| `ConfigurationUpdated` | `fintech.configuration.events` | Todos los módulos (cache invalidation) |

## Entidades

| Tabla | Descripción |
|---|---|
| `configuration.config_parameters` | Parámetro: key, value, product_type, version, status, effective_date |
| `configuration.config_audit_trail` | Historial de cambios por parámetro |

## Ciclo de parámetros

```
DRAFT → PENDING_APPROVAL → ACTIVE (en effectiveDate) → DEPRECATED
```

Nunca se elimina un parámetro — se marca `DEPRECATED`.

## Configuración

```yaml
fintech:
  configuration:
    cache-ttl-seconds: 300
```

# D1 — Channels [Supporting]

**Estado:** ⬜ Pendiente (Módulo 5)  
**Tipo:** Supporting  
**Schema DB:** `channels`  
**Paquete Java:** `com.fintech.channels`

## Responsabilidad

Punto de entrada del sistema. Captación de clientes y routing a Origination. **Sin lógica de decisión crediticia.**

## Tipos de canal

`DIGITAL_APP` · `WEB_PORTAL` · `BRANCH` · `API_PARTNER`

## Comunicación

- Llama T1 síncronamente para validar sesión antes de abrirla
- Publica `ApplicationStarted` → D3 Origination

## Eventos publicados

| Evento | Consumidor |
|---|---|
| `ApplicationStarted` | D3 Origination |
| `LeadCreated` | T3 Audit |
| `SessionExpired` | T3 Audit, T2 Notifications |

## Reglas

- Sin lógica de decisión crediticia — solo enrutamiento
- Cada sesión validada por T1 antes de abrirse
- `SessionExpired` publicado cuando TTL vence (configurable T5)

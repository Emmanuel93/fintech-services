# `docs/modules/` — specs históricas, **no** el estado actual

> ⚠️ **Este directorio está superado.** Son las especificaciones que se escribieron **antes** de
> implementar cada servicio. No se han mantenido desde entonces.

**No leas de aquí el estado de nada.** Seis de estas fichas dicen «⬜ Pendiente» sobre servicios que
llevan meses implementados y probados —`wallet` es el ejemplo más claro: dice pendiente y tiene 38
tests en verde—, y **faltan cuatro dominios completos** (risk, sales-org, invoicing, beneficiary),
que es la señal de cuándo se dejó de usar la convención.

## Dónde está lo vigente

| Qué buscas | Dónde |
|---|---|
| Estado de cada servicio | [tabla del README raíz](../../README.md#3-servicios-24--gateway--observabilidad--estado) |
| Especificación por dominio | [`docs/dominios/`](../dominios/) |
| Avance por entregable | [`docs/IMPLEMENTATION_TRACKER.md`](../IMPLEMENTATION_TRACKER.md) |
| Contrato de API de un servicio | el `README.md` del servicio |

## Por qué se conserva

Guarda el razonamiento **previo** a construir cada módulo, que a veces explica por qué algo quedó
como quedó. Eso tiene valor histórico; el estado actual no está aquí.

No se borró ni se actualizó: actualizarlo significaría mantener dos jerarquías de documentación de
dominio en paralelo, y ya se vio a dónde lleva eso — a que diverjan y que nadie sepa cuál creer.

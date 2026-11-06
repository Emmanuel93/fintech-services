# Laboratorio del cierre nocturno

La cadena completa —foto diaria de cartera → devengo por cuenta en cargos → cierre de cartera → corte por
cuenta en closing → estado de cuenta— sobre una **copia** de la base del stack local, con el reloj de
negocio desplazable. Sirve para correr semanas de cierres en minutos, ver cortes semanales, quincenales y
mensuales, y ensayar reparaciones antes de tocar la base real.

No comparte red, broker ni base con el stack local. Las imágenes son las mismas (`fintech/*:latest`).

```bash
tools/lab/lab.sh free-memory                 # apaga servicios del stack local que aquí no se usan (~3 GB)
tools/lab/lab.sh infra && tools/lab/lab.sh clone   # Postgres/Kafka del lab + copia de los esquemas
docker exec -i lab-postgres-1 psql -U fintech -d fintech < tools/lab/repair-phantoms.sql   # opcional: ensayo
tools/lab/lab.sh services                    # cartera, cargos, closing, estados de cuenta
tools/lab/lab.sh run 2026-11-01 2026-12-31 tools/lab/pagos.csv
tools/lab/lab.sh trace <cuenta> 2026-11-15   # la vista paso a paso de una cuenta en un día
tools/lab/lab.sh statements 2026-11-30 ~/Documents/material-kredius/pruebas/lab/2026-11-30
tools/lab/lab.sh down && tools/lab/lab.sh restore-live
```

`pagos.csv`: `fecha,cuenta,monto,referencia`, una línea por pago; el script pone el reloj en esa fecha
antes de aplicarlo, así su fecha valor es la del día.

Cómo corre un día `D`: reloj en `D` → pagos de `D` → reloj en `D+1` → `run-day-close?date=D` en cargos
(que pide la foto a cartera y reparte el devengo) → cartera cierra `D` al recibir el aviso → closing corta
`D` → el estado de cuenta se genera con el corte. El script espera a que el corte selle antes de seguir.

Puertos en el host: cartera 18087 · cargos 18088 · closing 18103 · estados de cuenta 18106 · Postgres 15432.

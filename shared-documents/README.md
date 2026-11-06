# shared-documents

Biblioteca (no un servicio) con lo que comparten los documentos que imprime la plataforma: estados de cuenta
(statement-service) y recibos de pago (receipt-service). Cada servicio despliega por su cuenta; esta es la única
implementación de cómo se hace un PDF.

- `DocumentModel`: parámetros y filas con las que se llena una plantilla (texto ya formateado en es-MX).
- `Formats`: dinero, fechas, periodos, tasas, en es-MX.
- `Brand`: la paleta del sistema de diseño por rol y cómo se cambia por la configurada antes de compilar.
- `pdf.JasperDocumentEngine`: compila las plantillas de un servicio (`reports/<archivo>.jrxml`) con su paleta, las llena
  con el logotipo y exporta a PDF; la versión de cada plantilla es su nombre y el hash de lo compilado.
- Recursos: Inter (`fonts/`), el logotipo (`branding/`), y la configuración de Jasper (extensión de fuentes y compilador
  JDT, que viaja en el jar porque la imagen corre con un JRE).

Las plantillas (`.jrxml`) viven en cada servicio y las genera `tools/estados-de-cuenta/gen_plantillas.py`. El
`Dockerfile` copia este módulo para todos los servicios (lo incluye `settings.gradle.kts`).

```bash
./gradlew :shared-documents:test
```

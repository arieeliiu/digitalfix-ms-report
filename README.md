# DigitalFix — Report

En la Evaluación 2 este microservicio consume comandos RabbitMQ y genera informes técnicos PDF de órdenes **CERRADAS**. Java 21, Spring Boot 4.1.1, Spring AMQP y PDFBox 3.0.8. Kafka, analítica y KPI quedan para una etapa posterior.

No tiene endpoints HTTP, SMTP ni base de datos. El documento se construye a partir del comando recibido, sin consultar Workorders/Catalog ni inventar nombres, diagnósticos o precios.

## Ejecución y seguridad

Requisitos: infraestructura del Paso 1 operativa con `rabbitmq1` y `rabbitmq2`, vhost `digitalfix` y red Docker externa `digitalfix-messaging`.

```powershell
Copy-Item .env.example .env
# Completar usuario propio y contraseña antes de continuar.
docker compose up -d --build
docker compose logs --tail 100 report
```

| Variable | Valor / finalidad |
|---|---|
| `RABBITMQ_ADDRESSES` | `rabbitmq1:5672,rabbitmq2:5672` por defecto |
| `RABBITMQ_USERNAME` | Cuenta exclusiva, por ejemplo `digitalfix_report` |
| `RABBITMQ_PASSWORD` | Contraseña de esa cuenta |
| `RABBITMQ_VHOST` | Compose fija `digitalfix` |
| `REPORT_STORAGE` | `/data`; configurable para Java fuera de Compose |
| `REPORT_FONT` | `/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf`; configurable para Java local |

Compose conserva documentos en `digitalfix-report_report-data` y no publica puertos. Java se ejecuta con UID/GID 10003. En Linux, `/data` tiene permisos 700 y documentos/registros 600. `.env`, PDFs y evidencias locales están excluidos de Git; `.env` también se excluye del contexto Docker.

Crear el usuario propio sin etiquetas administrativas ni permisos en otros vhosts. No reutilizar las cuentas de Infra o Notify. En Management UI (**Admin → Users**), permisos exclusivamente en `digitalfix`:

Configure y Read:

```text
^(cmd\.(direct|topic|dead\.dlx)|q\.cmd\.report(\.dlq)?)$
```

Write:

```text
^(cmd\.dead\.dlx|q\.cmd\.report(\.dlq)?)$
```

Los permisos sobre exchanges compartidos permiten declararlos/enlazarlos; no se conceden permisos sobre colas Notify. Write sobre DLX es necesario para configurar dead-lettering. Report no publica comandos en direct/topic. El script de integración puede dar de alta la cuenta con `--crear-usuario` si no existe; no sobrescribe contraseñas ni `.env` existentes.

## Contrato exacto para Workorders (Paso 5)

Publicar **una sola vez**, normalmente en `cmd.direct` con key **`report.gen`**. Alternativa: `cmd.topic` y una key compatible con `report.*`. No enviar simultáneamente por ambas rutas. Usar JSON UTF-8, `content_type=application/json`, mensaje persistente (`delivery_mode=2`) y preferiblemente `message_id=eventId`. El productor deberá usar confirms, `mandatory=true` y manejar retornos.

Ejemplo sintético:

```json
{
  "schemaVersion": "1.0",
  "type": "GENERAR_REPORTE",
  "eventId": "550e8400-e29b-41d4-a716-446655440000",
  "timestamp": "2026-10-17T18:00:00Z",
  "traceId": "550e8400-e29b-41d4-a716-446655440001",
  "correlationId": "15",
  "payload": {
    "ordenId": 15,
    "estado": "CERRADA",
    "solicitanteId": "oid-del-solicitante",
    "tecnicoId": "identificador-del-tecnico",
    "servicioId": 2,
    "descripcion": "Revisión y reparación de instalación eléctrica",
    "direccion": "Valparaíso, Chile",
    "fechaCreacion": "2026-10-15T12:00:00Z",
    "fechaCierre": "2026-10-17T18:00:00Z",
    "repuestos": [{"repuestoId": 4, "cantidad": 2}]
  }
}
```

Todos los campos son obligatorios; `repuestos: []` es válido. No hay campos opcionales adicionales en 1.0 y se rechazan propiedades desconocidas. No incluir JWT, credenciales ni entidades JPA completas.

| Campo | Origen / validación |
|---|---|
| `schemaVersion`, `type` | Exactamente `1.0` y `GENERAR_REPORTE` |
| `eventId`, `traceId` | UUID; nuevo eventId por comando, conservado al reintentar |
| `timestamp` | Instant de emisión, ISO-8601 UTC |
| `correlationId` | Decimal exacto de ordenId, sin ceros iniciales |
| `ordenId` | `OrdenTrabajo.id`, Long positivo |
| `estado` | Exactamente `CERRADA` |
| `solicitanteId` | ID estable de Entra, no correo, no vacío, máximo 100 caracteres |
| `tecnicoId` | Identificador conservado por Workorders, no vacío, máximo 100 caracteres |
| `servicioId` | Long positivo; no se exige nombre del servicio |
| `descripcion` | No vacía, máximo 1000 caracteres |
| `direccion` | No vacía, máximo 300 caracteres |
| `fechaCreacion` | `OrdenTrabajo.fechaCreacion`, Instant ISO-8601 |
| `fechaCierre` | Copia de fechaActualizacion registrada al pasar a CERRADA; no anterior a creación |
| `repuestos` | Lista con repuestoId Long positivo y cantidad Integer positivo |

Límites operativos: hasta 256 KiB por mensaje y 1000 repuestos. Cantidades fraccionarias se rechazan, no se redondean. Conservar el sobre completo en una reentrega: cambiar timestamp o traceId manteniendo eventId también constituye un conflicto de identidad.

El contrato se contrastó con `OrdenTrabajo` y `RepuestoOrden`. Report valida el comando, pero no consulta Oracle para verificar el estado. La publicación desde Workorders se implementará en el Paso 5, después del cierre confirmado.

## Topología Spring AMQP

| Recurso | Configuración |
|---|---|
| `cmd.direct` | direct, durable, sin auto-delete |
| `cmd.topic` | topic, durable, sin auto-delete |
| `cmd.dead.dlx` | direct, durable, sin auto-delete |
| `q.cmd.report` | classic durable, DLX cmd.dead.dlx, dead key report.dead |
| `q.cmd.report.dlq` | classic durable, sin auto-delete |
| Binding direct | cmd.direct → principal, report.gen |
| Binding topic | cmd.topic → principal, report.* |
| Binding DLQ | cmd.dead.dlx → DLQ, report.dead |

`RabbitConfig` declara únicamente exchanges compartidos y colas/bindings propios. Las propiedades coinciden con Infra. No borra/recrea recursos incompatibles ni declara colas/bindings de Notify.

## Procesamiento y persistencia

1. `@RabbitListener` recibe `Message` crudo, ACK manual, prefetch 1 y un consumidor. Los errores de JSON pasan por el procesamiento controlado.
2. `Procesador` valida el DTO y calcula SHA-256. Espacios y orden de propiedades JSON no alteran la identidad; valores diferentes u otro orden de repuestos sí.
3. Reserva `/data/<eventId>.identidad`. Un mismo eventId con otra huella se rechaza.
4. Guarda `/data/<eventId>.pdf`, o reutiliza el documento existente tras un fallo previo, comprobando su metadato eventId.
5. Guarda `/data/<eventId>.ok` con SHA-256 del PDF. Solo después ejecuta `basicAck`.

El informe incluye datos disponibles, fechas ISO-8601 identificadas como UTC, fecha de generación, eventId y traceId. La fuente Unicode se incrusta; el contenido se ajusta al ancho real y se pagina en A4. Un carácter sin glifo produce error permanente en lugar de sustituirse silenciosamente.

| Situación | Tratamiento |
|---|---|
| JSON/contrato inválido, tipo/estado incorrecto, identidad conflictiva | NACK inmediato, multiple=false, requeue=false |
| IOException de generación/almacenamiento | Hasta 3 intentos por entrega, esperas de 2 y 5 segundos |
| Tercer fallo | NACK sin requeue, envío a DLQ |
| Duplicado completado con PDF íntegro | ACK sin regenerar/regrabar |
| Fallo del ACK por desconexión | Se propaga; la reentrega se deduplica desde disco |
| PDF completado ausente/alterado | Rechazo; requiere revisión, no ACK silencioso |

Los logs registran eventId, traceId, ordenId, intento y motivo; no imprimen payloads ni trazas con datos personales. Un JSON ilegible puede impedir recuperar identificadores. El retry automático de Spring está deshabilitado.

Cada archivo se escribe en un temporal del mismo directorio, se sincroniza a disco y se renombra atómicamente. En Linux se sincroniza también el directorio. No es una transacción única entre identidad, PDF y éxito: el orden permite recuperar un PDF cuando falta `.ok`. No se adopta un documento huérfano sin identidad.

`report.lock` mantiene bloqueado el volumen y el procesamiento está serializado. **Una sola instancia con volumen local exclusivo**; no escalar usando volúmenes independientes ni un filesystem sin file locks/rename atómico. No borrar registros para forzar reprocesamientos. Respaldar PDF e identidad/éxito juntos.

Reiniciar/recrear el contenedor conserva archivos. No ejecutar `docker compose down -v`. Los tres intentos se cuentan por entrega: una caída antes de ACK/NACK puede reiniciar ese contador al reentregarse. No se promete exactamente una vez ni alta disponibilidad; dos nodos no replican colas classic. El dead-lettering classic puede perder mensajes si el destino no está disponible; monitorizar broker y DLQ.

## Recuperación para EP4

Acceso desde Docker en un host autorizado; no hay descarga HTTP anónima.

```powershell
docker compose exec -T report ls -l /data
# Sustituir por un eventId real.
$evento = '550e8400-e29b-41d4-a716-446655440000'
New-Item -ItemType Directory -Force evidencias-locales | Out-Null
docker compose cp "report:/data/$evento.pdf" "evidencias-locales/$evento.pdf"
docker compose exec -T report cat "/data/$evento.ok"
docker compose exec -T report sha256sum "/data/$evento.pdf"
```

Los dos hashes deben coincidir. Abrir el PDF con un lector estándar. Restringir acceso al host y usar solo datos sintéticos para capturas públicas.

### Reproceso controlado desde DLQ

No hay bucle automático DLQ → principal. Corregir primero el fallo de almacenamiento y conservar el cuerpo/eventId exactos. No cambiar contenido de una identidad ya reservada.

Procedimiento AMQP con credenciales de un operador autorizado:

1. Consumir el mensaje seleccionado de q.cmd.report.dlq con ACK manual, verificar eventId y guardar una copia segura. Si no coincide, NACK con requeue y detenerse.
2. Publicar el cuerpo original en cmd.direct / report.gen, persistente, con mandatory=true y publisher confirms. No copiar ciegamente las cabeceras internas x-death.
3. Ante retorno o falta de confirmación positiva, no confirmar la entrega de DLQ.
4. Tras confirmar publicación, ACK solo de la entrega seleccionada. Verificar PDF y `.ok`. Nunca purgar la DLQ. Una caída entre publicación y ACK puede duplicar el comando; Report lo deduplica.

El script demuestra recuperación en un entorno exclusivo: copia local, publicación por Management API, espera de éxito persistido y retirada de su mensaje sintético identificado. No es una herramienta de replay para colas con operadores concurrentes. Conflictos de identidad/PDFs alterados requieren investigar y restaurar el dato original; reintentar sin resolverlos vuelve a DLQ.

## Pruebas

Unitarias con Java 21:

```powershell
.\mvnw.cmd -B test
```

En Windows usan Arial del sistema; en Linux, DejaVu Sans. Alternativa Docker:

```powershell
docker build --target pruebas -t digitalfix-report:pruebas .
docker run --rm digitalfix-report:pruebas
```

Integración real con Python 3 y Docker, solo en el entorno local de evaluación:

```powershell
# Detener únicamente Report si ya estaba activo; mantener RabbitMQ funcionando.
docker compose stop report
python scripts/verificar_integracion.py
# Para alta inicial de cuenta: añadir --crear-usuario.
```

El script exige principal/DLQ vacías y sin consumidores. Lee credenciales de Infra en memoria, sin modificar sus archivos. Publica datos sintéticos por Management API, comprueba consumo real, provoca fallos de escritura en `/data`, restaura permisos, recrea Report conservando volumen y compara topología de Notify. No elimina colas, volúmenes ni mensajes ajenos. No ejecutarlo con actividad de negocio u operadores concurrentes.

Salidas ignoradas en `evidencias-locales/`: resultados JSON, log, evento sintético y PDF largo. El servicio queda ejecutándose. La suite abre PDFs con PDFBox; el documento real también se validó con un lector independiente y revisión visual.

Resultados y matriz parcial EP3/EP4: [docs/EVIDENCIAS.md](docs/EVIDENCIAS.md).

## Organización y pendientes

- `config`: topología Spring AMQP.
- `dto/Contrato`: sobre y datos compatibles con Workorders.
- `messaging/Consumidor`: ACK/NACK manual.
- `service`: validación, reintentos, persistencia y PDF.
- `src/test`: contenido, identidad, recuperación, ACK y errores.
- `scripts/verificar_integracion.py`: pruebas contra el clúster real.

Pendientes fuera del Paso 4: publicación desde Workorders, frontend/BFF, AWS y Kafka. No se modificaron otros repositorios. SMTP real de Notify y Entra real de Rabbit Admin siguen como validaciones independientes. El usuario crea el PR y decide el merge.

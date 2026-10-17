# Evidencias EP3/EP4 — Paso 4: Report

Fecha: 17 de octubre de 2026. Rama: `feature/rabbitmq-report`.

## Pruebas ejecutadas

`./mvnw.cmd -B test`: **15 pruebas, 0 fallos, 0 errores, 0 omitidas; BUILD SUCCESS**. La última ejecución incluyó rechazo de cantidades fraccionarias.

Integración: `python scripts/verificar_integracion.py --crear-usuario`, contra el clúster local del Paso 1. Imagen de producción sin puertos publicados, usuario exclusivo `digitalfix_report`, sin etiquetas administrativas. Publicación sintética mediante Management API; consumo, ACK/NACK y dead-lettering reales mediante AMQP y `@RabbitListener`.

| Funcionalidad | Evidencia y resultado |
|---|---|
| Topología | Principal/DLQ classic durables, argumentos correctos y listener activo |
| Direct | cmd.direct / report.gen produjo PDF y `.ok` |
| Topic | cmd.topic / report.prueba produjo PDF largo |
| PDF real | Extraído del contenedor: 5 páginas A4, PDF 1.6 |
| Acentos y contenido | PDFBox en tests; pdfplumber en PDF real: Ñuñoa, Valparaíso, Revisión, estado, repuesto 120, eventId y traceId presentes |
| Paginación | Cinco páginas renderizadas con Poppler y revisadas visualmente; límites de caracteres comprobados automáticamente |
| Idempotencia | Repetir evento conservó SHA-256 y fecha de modificación |
| Persistencia | Recreación con el mismo volumen conservó PDF y deduplicación |
| Fallo después del PDF | Unitario con disco real y fallo al completar: reapertura reutiliza bytes/fecha y guarda `.ok` |
| ACK tras éxito | Unitario: generar → completar → ACK con `.ok`; integración terminó sin mensajes pendientes |
| Fallo del ACK | Unitario: no NACK tras fallo de ACK; reentrega sin regenerar PDF |
| JSON inválido | DLQ real con x-death.reason=rejected |
| Estado incorrecto | ASIGNADA rechazado a DLQ con x-death |
| Identidad conflictiva | Mismo eventId con descripción distinta a DLQ; PDF anterior intacto |
| Recuperación transitoria | Error real al retirar escritura de /data; restaurarla permitió completar en segundo intento |
| Reintentos agotados | Tres errores reales, esperas 2/5 segundos y DLQ; logs de los tres intentos |
| Reproceso | Permisos restaurados; cuerpo original republicado con mismo eventId, `.ok` verificado antes de retirar mensaje sintético |
| Repuestos vacíos | Unitario: PDF indica ausencia de repuestos registrados |
| Concurrencia | Segundo registro sobre el mismo volumen no adquiere bloqueo |
| Integridad | PDF completado alterado se rechaza |
| Compatibilidad Notify | Colas/bindings y exchanges compartidos comparados antes/después: sin cambios |

No se consumieron ni purgaron mensajes de Notify; no se validó SMTP. No se borraron colas funcionales ni volúmenes ni se modificaron repositorios vecinos.

## Reproducción

Las evidencias locales ignoradas incluyen resultados.json, report.log, evento-largo.json, informe-largo.pdf y cinco imágenes de páginas. Los datos son sintéticos; ningún PDF se genera automáticamente al arrancar.

Seguir los comandos del README cuando Report esté detenido y sus colas vacías. Para EP4: publicar un nuevo UUID por una sola ruta, mostrar log y colas en Management UI, extraer el PDF; repetir el sobre exacto para mostrar que no se reescribe y recrear Report conservando el volumen.

Las esperas unitarias son simuladas para comprobar 2000/5000 ms; la integración sí esperó los intervalos reales. La recuperación tras guardar PDF y antes de `.ok` es un fallo unitario inyectado, no una interrupción física del host.

## Pendientes y límites

- Paso 5: publicar desde Workorders tras cierre confirmado. No se ejecutó un cierre real Oracle → Report.
- AWS, frontend/BFF, Kafka y descarga HTTP fuera de alcance.
- Sin pruebas de failover classic, pérdida de energía, corrupción física o restauración de backups. Sin promesa de HA/exactamente una vez.
- SMTP de Notify y tokens reales de Rabbit Admin mantienen sus pendientes previos.

Esta matriz actualiza parcialmente las funcionalidades del proyecto desde Report para respetar el alcance de un solo repositorio.

# digitalfix-ms-report

Reportes y analítica mediante Kafka, contempladas para una fase futura del caso DigitalFix.

## Estado de esta fase

Este repositorio contiene únicamente documentación: no existen código Java,
`pom.xml`, Maven Wrapper, endpoints ni configuración de brokers que compilar o ejecutar.
No participa en los flujos funcionales actuales.

La arquitectura activa es Angular con Entra ID/JWT → AWS API Gateway → BFF →
Workorders/Catalog → Oracle. Toda comunicación de negocio es síncrona por HTTP.

## Fuera de alcance

La implementación de reportes y analítica con Kafka queda pendiente de una decisión
explícita en una fase posterior. No se agregan dependencias, producers, consumers,
listeners, queues, exchanges, topics, brokers, Zookeeper ni servicios Docker.
No se crean clases placeholder ni abstracciones de mensajería.

Cuando exista una implementación, separar controller (si expone HTTP), service,
DTOs y configuración/clients según responsabilidades reales. Los contratos de
mensajería, seguridad, reintentos y persistencia deberán definirse entonces;
no se anticipan durante este refactor.

El orden de revisión fue BFF → Workorders → Catalog → Notify → Audit → Report.
Las tres aplicaciones existentes se validan con Maven; este módulo documental
no tiene una comprobación de compilación aplicable.

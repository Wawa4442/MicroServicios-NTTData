# Laboratorio 5 — Cocina y mensajería confiable (TC-25 a TC-30)

Documentación en lenguaje natural de los 6 retos resueltos sobre el proyecto
`tacocloud` (reactor multi-módulo). Complementa a `LABORATORIO_1_TC-01_TC-06.md`,
`LABORATORIO_2_TC-07_TC-12.md`, `LABORATORIO_3_TC-13_TC-18.md` y
`LABORATORIO_4_TC-19_TC-24.md`.

---

## Resumen en una frase

El Laboratorio 5 convierte el "mandé un mensaje" en un flujo que se puede
auditar y recuperar: la orden avanza por una **máquina de estados con roles y
versión** que nadie puede saltarse ni asignar desde el cliente (TC-25), la
cocina reclama tickets con una **escritura atómica que reparte sin duplicar**
y estima tiempos con una fórmula de configuración (TC-26), los cuatro brokers
hablan el **mismo evento versionado sin datos sensibles** (TC-27) elegido por
**propiedad en runtime** en vez de editando el POM (TC-28), la creación
guarda **orden y evento en la misma transacción** y un publicador de fondo
entrega después (TC-29), y la cocina consume ** deduplicando por eventId,
reintentando solo lo transitorio y estacionando lo venenoso en una DLQ
visible con replay controlado** (TC-30).

---

## Cambios archivo por archivo

### 1. TC-25 — Flujo de estados de una orden (`tacos`, `tacos.workflow`)

- `tacocloud-domain-mongodb/.../tacos/OrderStatus.java` (nuevo): los siete
  estados pedidos — `CREATED, ACCEPTED, PREPARING, READY, OUT_FOR_DELIVERY,
  DELIVERED, CANCELLED`.
- `tacocloud-domain-mongodb/.../tacos/OrderStatusChange.java` (nuevo): un
  renglón de auditoría por cambio — de dónde venía, a dónde fue, quién
  (`changedBy`), cuándo, por qué canal (`origin`: API, KITCHEN, EVENT) y un
  motivo corto. No lleva usuario completo, ni pago, ni contraseñas: la
  auditoría explica sin convertirse en una segunda copia de datos sensibles.
- `tacocloud-domain-mongodb/.../tacos/TacoOrder.java`: la orden ahora tiene
  `status` (empieza en `CREATED`), `@Version` para que dos escritores
  concurrentes choquen con 409 en vez de pisarse en silencio,
  `statusHistory` (la lista ordenada de cambios), y `stationId`/`cookId`
  (a quién le tocó el ticket). Nuevo índice `order_status_placed_idx`
  para la cola de cocina.
- `tacocloud-api/.../tacos/workflow/OrderStatusPolicy.java` (nuevo): la
  **única** tabla de transiciones y de roles. El camino feliz es
  `CREATED→ACCEPTED→PREPARING→READY→OUT_FOR_DELIVERY→DELIVERED`; cancelar
  se puede desde `CREATED` y `ACCEPTED` siendo el dueño, y más tarde solo
  siendo operador. Repetir el estado actual es idempotente (éxito sin
  escribir). Reglas de rol en un solo lugar: el cliente solo cancela lo
  suyo temprano, la cocina avanza pero jamás cancela ni toca propiedad o
  pago, el operador puede cualquier movimiento legal, y nadie sin sesión
  cambia estados.
- `tacocloud-api/.../tacos/workflow/OrderWorkflowService.java` (nuevo): el
  caso de uso central que usan el controlador, la cocina y el consumidor de
  eventos. Carga, verifica propiedad, valida matriz y rol, agrega el
  renglón de auditoría y guarda — todo en una sola cadena reactiva, sin
  `block()` ni `subscribe()`. El motivo se recorta y se acota a 280
  caracteres.
- `tacocloud-api/.../tacos/workflow/OrderStatusChangeRequest.java` y
  `OrderCancelRequest.java` (nuevos): lo único que el cliente manda —
  a qué estado quiere ir y por qué. No hay forma de mandar el estado
  dentro del `OrderCreateRequest` (ese DTO sigue sin campo status).
- `tacocloud-api/.../tacos/web/api/OrderApiController.java`: dos rutas
  nuevas — `PATCH /api/orders/{id}/status` y `POST /api/orders/{id}/cancel`
  — que resuelven al llamante, pasan por el workflow y registran el evento
  en el outbox (TC-29) en vez de publicarlo en línea. La creación
  (`POST /api/orders` y `fromEmail`) ahora pasa por `OrderPlacementService`
  (orden + outbox en la misma transacción) y acepta `X-Correlation-ID`.
- `tacocloud-api/.../tacos/web/api/OrderApiService.java`: crear sigue
  igual, pero `merge` (PUT) ahora conserva estado, historial, versión y
  asignación de cocina — reemplazar el contenido jamás mueve el ciclo de
  vida. Y `deleteOrder` rechaza con 409 una orden que la cocina ya empezó
  (`PREPARING` en adelante): eso ya no se borra físicamente, se cancela
  por el flujo (cierra la deuda que el Lab 1 dejó anotada).
- `tacocloud-api/.../tacos/api/dto/OrderResponse.java` y
  `OrderSummaryResponse.java`: ahora muestran `status` (y `version` el
  detalle), para que el cliente vea dónde va su pedido.
- `tacocloud-api/.../tacos/api/dto/OrderMapper.java`: crear inicializa
  `CREATED`; reemplazar preserva lo del servidor.
- `tacocloud-api/.../tacos/web/api/CallerIdentity.java` y
  `CallerIdentityResolver.java`: la identidad ahora distingue el rol
  `KITCHEN` (antes solo sabía de ADMIN), con etiqueta de auditoría que
  nunca es un secreto.
- `tacocloud-api/.../tacos/web/api/RestProblemHandler.java`: dos códigos
  nuevos y estables — 409 `invalid_status_transition` (salto ilegal) y
  400 `invalid_status` (petición mal formada); el `OptimisticLockingFailure`
  existente se reutiliza como 409 `conflict`.
- `tacocloud-security/.../SecurityConfig.java`: la cocina (`/api/kitchen/**`)
  ahora admite `KITCHEN` y `ADMIN`, para que un operador pueda destrabar un
  ticket atorado con el mismo movimiento del ciclo de vida.

### 2. TC-26 — Cola de cocina, claim atómico y tiempo estimado (`tacos.kitchen`)

- `tacocloud-api/.../tacos/kitchen/KitchenProperties.java` (nuevo): los
  números de la estimación y la paginación como configuración
  (`tacos.kitchen.*`), no como literales en el código.
- `tacocloud-api/.../tacos/kitchen/EtaCalculator.java` (nuevo): la fórmula
  `base + porTaco*tacos + porIngrediente*ingredientes + porCola*profundidad`,
  redondeada hacia arriba. Mismos datos, mismo número: es una ayuda de
  planeación para la pantalla de cocina, nunca una promesa al cliente.
- `tacocloud-api/.../tacos/kitchen/KitchenOrderResponse.java` (nuevo): la
  vista segura del ticket — nombres, conteos, ciudad/estado para reparto,
  asignación y ETA. Sin calle, sin ZIP, sin referencia de pago, sin usuario:
  una pantalla que no ve una tarjeta no la puede filtrar.
- `tacocloud-api/.../tacos/kitchen/KitchenQueueService.java` (nuevo): listar
  es una consulta filtrada (`CREATED`, `placedAt` + `_id` ascendentes, con
  su conteo) y reclamar es **un solo `findAndModify` condicionado a
  `CREATED`** que pasa a `ACCEPTED` y estampa estación/cocinero más el
  renglón de auditoría. No hay leer-y-luego-guardar: Mongo decide al
  ganador y dos estaciones jamás cocinan lo mismo.
- `tacocloud-api/.../tacos/kitchen/KitchenClaimRequest.java` (nuevo):
  estación/cocinero opcionales; si no se dicen, se usa la identidad
  autenticada para que la asignación nunca sea anónima.
- `tacocloud-api/.../tacos/kitchen/KitchenQueueEmptyException.java` (nuevo):
  404 `kitchen_queue_empty` para "ya están al día", distinto de un error.
- `tacocloud-api/.../tacos/web/api/KitchenGatewayController.java`
  (reescrito): `GET /api/kitchen/queue` (paginado FIFO),
  `POST /api/kitchen/queue/claim` (el ganador atómico),
  `PATCH /api/kitchen/orders/{id}/status` (avance por el workflow central
  + registro en outbox). Se conserva `GET /api/kitchen/orders` como alias
  del equipo de pantalla anterior.

### 3. TC-27 — Contrato único de eventos (nuevo módulo `tacocloud-messaging-contract`)

- `tacocloud-messaging-contract/.../tacos/messaging/OrderEventType.java`:
  `ORDER_CREATED, STATUS_CHANGED, ORDER_CANCELLED`. Los tipos se agregan,
  nunca se renombran ni se reutilizan.
- `.../OrderEventPayload.java`: la foto segura para cocinar — ids, estado
  y estado previo, nombres y conteo, moneda y total, fecha, ciudad/estado y
  estación. Sin calle, sin ZIP, sin pago, sin usuario, sin contraseñas.
  Campos nuevos siempre opcionales: un consumidor v1 ignora lo que no
  conoce en vez de romperse.
- `.../OrderEvent.java`: el sobre versionado — `eventId` (UUID, la llave
  de idempotencia de punta a punta), `eventType`, `version` (`"v1"`),
  `occurredAt`, `correlationId` (amarra el HTTP con el evento y sus logs)
  y el payload.
- `.../OrderMessagingService.java`: **el puerto único** que los cuatro
  adaptadores implementan — `Mono<Void> sendEvent(OrderEvent)`. Se
  eliminaron las cuatro copias duplicadas de la interfaz (una por
  transporte) y los convertidores dejaron de serializar la entidad Mongo
  `TacoOrder`. El contrato solo depende de Jackson y Reactor: nada de
  Spring, Mongo, JMS, Rabbit o Kafka.
- `tacocloud-api/.../tacos/messaging/OrderEventMapper.java` (nuevo): vive
  en el API (no en el contrato, para no contaminarlo con tipos de
  dominio) y traduce la entidad al evento seguro.
- Política de evolución documentada en el código y probada: serialización
  v1 de ida y vuelta, prueba negativa de sensibles, compatibilidad con
  campo adicional, unicidad de ids y snapshot que detecta cambios
  incompatibles.

### 4. TC-28 — Elegir broker en runtime (`tacos.messaging`)

- Cada adaptador ahora lleva su condición:
  `NoOp...(matchIfMissing = true)`, `Jms...(havingValue = "jms")`,
  `Rabbit...("rabbit")`, `Kafka...("kafka")` — exactamente un bean activo
  por valor de `tacocloud.messaging.transport`, sin tocar el POM.
- `tacocloud-api/.../tacos/messaging/MessagingTransportProperties.java`
  (nuevo): la propiedad más destinos por adaptador, todo externalizado.
- `tacocloud-api/.../tacos/messaging/MessagingTransportValidator.java`
  (nuevo): falla el arranque con mensaje claro ante valor desconocido, y
  prohíbe `noop` silencioso en perfil `production`.
- `JmsMessagingConfig` / `RabbitMessagingConfig`: renombradas desde la
  `MessagingConfig` que JMS y RabbitMQ declaraban con el **mismo nombre
  calificado** (imposible tener ambos en el classpath hasta este cambio);
  cada una detrás de su condición de transporte, con convertidores del
  evento versionado.
- `tacocloud-api/pom.xml`: depende del contrato siempre, de `noop` siempre,
  y de JMS/Rabbit/Kafka como **opcionales** — las pruebas unitarias
  arrancan sin broker y el operador elige transporte por propiedad.
- `application.yml`: destinos por variables de entorno y **credenciales
  fuera de Git** (`${ARTEMIS_PASSWORD:}` en vez de los passwords de
  ejemplo que el baseline traía escritos).

### 5. TC-29 — Outbox transaccional (`tacos.outbox`)

- `tacocloud-api/.../tacos/outbox/OutboxEvent.java` (nuevo): la fila local
  — `eventId` único, `aggregateId`, tipo, versión, payload JSON del
  contrato seguro, correlación, estado (`NEW/PUBLISHING/PUBLISHED/FAILED`),
  intentos, marcas de tiempo, próximo intento, último error y quién la
  reclamó.
- `.../OutboxRepository.java`, `.../OutboxProperties.java` (nuevos):
  acceso y sintonía (lote, intervalo, reintentos máximos, backoff base con
  crecimiento exponencial).
- `.../OutboxService.java` (nuevo): `append` (guarda `NEW`),
  `claimBatch` (reclamos por escritura condicional para que dos relays no
  compartan fila), `markPublished` / `markFailed` (reintento con backoff;
  agotado queda `FAILED` visible, no se esfuma).
- `.../OrderPlacementService.java` (nuevo, `@Transactional`): crear orden
  + registrar outbox en la **misma transacción local**. Fallo antes del
  commit = cero orden y cero outbox; orden confirmada = fila `NEW`
  garantizada. El HTTP responde tras el commit local; el broker se entera
  después por el relay (al menos una vez; deduplica TC-30).
- `.../OutboxRelay.java` (nuevo, `@Scheduled`): el borde programado —
  el único `subscribe()` nuevo y está donde la regla lo permite (el
  scheduler, no un servicio). Reclama, envía por el transporte activo,
  marca el resultado; tolera apagones del broker y reinicios.
- `.../MongoTransactionConfig.java` (nuevo): `ReactiveMongoTransactionManager`
  + `@EnableScheduling`, con la receta documentada: Mongo debe correr como
  **replica set** (las transacciones no existen en standalone).
- `tacocloud-api/.../tacos/reorder/ReorderService.java`: la reorden
  confirmada también registra su evento (antes pasaba por fuera del
  outbox).

### 6. TC-30 — Consumidor idempotente, retry limitado y DLQ (`tacos.consumer`)

- `tacocloud-api/.../tacos/consumer/ProcessedEvent.java` (+ repositorio):
  la marca de "ya procesado" con índice único en **`eventId`** — nunca en
  `orderId`, porque una orden produce muchos eventos distintos en su vida.
- `.../DeadLetter.java` (+ repositorio): el estacionamiento de venenosos
  — destino, tipo, versión, orden, correlación, causa, error, intentos y
  fecha. Sin datos sensibles. Nada aquí se reintenta solo: no hay loops
  infinitos.
- `.../ConsumerProperties.java` (nuevo): reintentos máximos, backoff,
  destino y DLQ, todo configurable.
- `.../ConsumerMetrics.java` (nuevo): contadores honestos — procesados,
  duplicados, reintentos y DLQ.
- `.../OrderEventConsumer.java` (nuevo): `consume` deduplica por
  `eventId` (duplicado = ack sin repetir negocio), aplica el efecto
  (`ORDER_CREATED` verifica existencia; `STATUS_CHANGED`/`ORDER_CANCELLED`
  reconcilian por el mismo workflow — un evento ya aplicado es no-op) y
  marca procesado **en la misma transacción** (un crash entre efecto y
  marca pierde ambos y el reenvío reintenta, o conserva ambos y el reenvío
  es no-op). Solo lo transitorio (optimistic-lock, timeouts, caídas de
  acceso) reintenta con tope; lo permanente, la versión desconocida y lo
  agotado van a DLQ. `replay(eventId)` reinyecta una carta tras arreglar
  la causa, sin inventar eventos a mano.
- `tacocloud-api/.../tacos/web/api/AdminDlqController.java` (nuevo):
  `GET /api/admin/dlq` y `POST /api/admin/dlq/{eventId}/replay` bajo
  `/api/admin/**` (solo ADMIN), recargando el payload desde el outbox.

### 7. Pruebas nuevas (78 en `tacocloud-api`, 3 en `security`, 5 en `contract`)

- `tacos/workflow/OrderStatusPolicyTest` (17): matriz parametrizada del
  camino feliz, saltos ilegales, terminales sin salida, cancelación
  temprana del dueño, cocina que avanza pero no cancela, cliente que no
  marca DELIVERED, ADMIN total, anónimo rechazado, error de transición vs
  prohibido, idempotencia de repetición.
- `tacos/workflow/OrderWorkflowServiceTest` (12): avance con historial,
  repetición sin guardar, salto ilegal sin guardar, 403 de rol,
  cancelación temprana/tardía/de operador, 404, 401, optimistic-lock que
  se propaga, motivo largo rechazado, auditoría sin sensibles.
- `tacos/workflow/OrderStatusControllerTest` (7): 200 de avance, 409 de
  salto, 403 de cliente a DELIVERED, 200 de cancelación, idempotencia sin
  `save`, 409 de versión obsoleta, forma de respuesta con status+versión.
- `tacos/web/api/OrderApiServiceTest` (+2): borrar en `PREPARING` es 409
  sin `deleteById`; borrar en `CREATED` sigue funcionando.
- `tacos/kitchen/EtaCalculatorTest` (3): crece con cola y tamaño,
  determinista, ticket vacío = base.
- `tacos/kitchen/KitchenQueueServiceTest` (5): FIFO estable con paginación,
  claim de una sola escritura condicional (nunca `save`), cola vacía =
  excepción tipificada, DTO sin sensibles, dos estaciones reclaman
  distinto.
- `tacos/web/api/KitchenGatewayControllerTest` (4): cola paginada segura,
  respuesta sin pago/calle, claim vacío = 404 `kitchen_queue_empty`,
  avance que delega al workflow central.
- `tacos/messaging/OrderEventMapperTest` (3) + `OrderEventContractTest`
  en el módulo de contrato (5): roundtrip v1, ausencia de PAN/CVV/password/
  usuario/calle, ids+versión siempre presentes, consumidor v1 ignora campo
  nuevo compatible, snapshot que frena cambios incompatibles.
- `tacos/messaging/MessagingTransportTest` (6, con `SelectorTestConfig`
  sin brokers): un bean por valor válido, `noop` por defecto, valor
  inválido frena con mensaje claro, `noop` prohibido en producción,
  explícito permitido, y sin secretos hardcodeados en el yml nuevo.
- `tacos/outbox/OutboxServiceTest` (7) + `OrderPlacementServiceTest` (2):
  confirmada siempre con fila `NEW`, fallo de broker reintentable con
  backoff, agotado visible en `FAILED`, éxito a `PUBLISHED`, recuperación
  tras caída, payload = contrato seguro, backoff exponencial; fallo antes
  del commit = cero orden + cero outbox.
- `tacos/consumer/OrderEventConsumerTest` (10): doble entrega un solo
  efecto, transitorio reintenta sin parquear, permanente a DLQ sin loop,
  versión desconocida estacionada, reconciliación idempotente, replay sin
  duplicar, llave = `eventId` no `orderId`, tipo desconocido y `eventId`
  ausente a DLQ, **crash entre efecto y marca reintenta en vez de perder**.
- `SecurityAuthorizationTest` (+3): status/cancel por rol autenticado,
  cola de cocina solo KITCHEN/ADMIN, DLQ solo ADMIN.
- `ReorderServiceTest` (actualizado): constructor con outbox mockeado —
  la reorden confirmada registra evento.

---

## Patrones utilizados

| Reto | Patrón |
|------|--------|
| TC-25 | **Máquina de estados explícita + servicio central de workflow**: la matriz, los roles y la auditoría viven en un solo sitio en vez de repartidos entre controladores; el `enum` solo es el vocabulario. |
| TC-26 | **Cola en base de datos + claim por `findAndModify` condicional**: el FIFO y el ganador los decide Mongo en una escritura, no la memoria de la app. |
| TC-27 | **Contrato versionado como módulo propio (Ports and Adapters)**: el evento es el puerto, los brokers son adaptadores intercambiables; el mapper vive fuera del contrato para no contaminarlo. |
| TC-28 | **Selección por propiedad + `@ConditionalOnProperty` + validación fail-fast**: el cableado lo decide el entorno, y un typo frena el arranque con mensaje en vez de arrancar torcido. |
| TC-29 | **Outbox transaccional + relay programado**: la decisión local (orden+fila) es atómica; la entrega es al menos una vez y corre en el borde scheduler. |
| TC-30 | **Consumidor idempotente por llave de evento + DLQ con replay explícito**: el índice único convierte "dos entregas" en "un efecto"; lo venenoso se estaciona visible en vez de reintentarse solo. |

## Garantías obtenidas

- **Ciclo de vida auditable:** todo cambio de estado pasa matriz + rol +
  propiedad, deja quién/cuándo/canal/motivo sin sensibles, y choca con 409
  ante escritores concurrentes (`@Version`).
- **Cocina sin duplicados ni filtraciones:** un ticket, un ganador atómico;
  la pantalla ve ETA y reparto, jamás pago ni domicilio completo.
- **Un solo evento para todos los brokers:** JSON versionado con ids,
  correlación y snapshots seguros; transporte elegible sin recompilar y
  secretos fuera de Git.
- **Nada confirmado se pierde:** orden + outbox en una transacción; el
  relay entrega con backoff y sobrevive reinicios; la entrega es al menos
  una vez y el consumidor la vuelve efectivamente una vez.
- **Venenosos visibles, no loops:** reintento acotado solo a lo
  transitorio; lo demás a DLQ con causa y replay de operador.
- **Sin `block()` ni `subscribe()` nuevos** en servicios/controladores —
  el único `subscribe()` está en el relay programado, que es borde de
  ejecución autorizado.

## Riesgos residuales (defensa técnica honesta)

1. **Transacciones Mongo exigen replica set.** Sin `--replSet`, el
   `ReactiveMongoTransactionManager` no puede prometer atomicidad
   orden+outbox ni efecto+marca. El código tiene la forma correcta y las
   pruebas verifican el encadenamiento, pero la garantía real aparece con
   la topología documentada.
2. **Status moves publican en dos pasos.** La creación es atómica
   (placement), pero `status → outbox.append` en controladores es
   secuencial: si el proceso muere entre ambos, el cambio existe sin su
   evento. Un `StatusChangePlacement` transaccional lo cerraría; quedó
   anotado como siguiente paso.
3. **Claim no usa `@Version`.** El ganador lo decide el `findAndModify`
   (correcto y suficiente), pero la fila reclamada no incrementa `version`;
   un escritor con copia vieja no chocará contra el claim por versión sino
   por matriz/estado.
4. **DLQ portable, no nativa por broker.** La DLQ vive en Mongo (visible y
   con replay) en vez de DLX de Rabbit / DLT de Kafka / DLQ de Artemis por
   separado. Un operador que busque la carta en la consola del broker no
   la verá ahí: está en `/api/admin/dlq`.
5. **ETA por offset, no por cursor.** Bajo inserciones concurrentes una
   fila puede moverse de ventana; la paginación es estable por clave
   secundaria pero no inmune a desplazamientos, igual que en el Lab 4.
6. **Los cuatro starters viajan como opcionales.** El classpath de
   desarrollo carga las clases de JMS/AMQP/Kafka aunque solo una esté
   activa; la condición impide instanciarlas, pero el escaneo existe.
7. **Sin Testcontainers en esta suite.** Outbox y consumidor se prueban con
   dobles sobre la forma de las consultas (`findAndModify` condicional,
   transacciones anotadas), no contra un Mongo real en replica set ni un
   broker real. La integración contra motor queda fuera del alcance
   automático, igual que en el Lab 4.

## Hallazgos de la sesión de pruebas (bugs reales que las pruebas atraparon)

- **La validación de motivo lanzaba fuera del `Mono`.** `transition`
  validaba antes de construir el publisher, así que el error salía como
  excepción síncrona y no como señal reactiva. Se envolvió en `Mono.defer`
  y la prueba de motivo largo lo fijó.
- **`tryClaim` devolvía al perdedor.** La primera versión devolvía el
  candidato cuando Mongo no encontraba fila, y el filtro posterior dejaba
  pasar al que perdió la carrera. Se cambió a vacío-en-derrota y la
  prueba de dos estaciones lo fijó.
- **La cocina publicaba `null`.** El test de avance construía el
  controlador con un `OutboxService` mockeado sin stub y el `append`
  devolvía `null` → 500. Stub explícito y 200 de nuevo.
- **El YAML escondía la propiedad.** `messaging/outbox/consumer` se
  anidaron bajo `tacos:` mientras las clases leen `tacocloud.*`: la app
  arrancaba con valores por defecto silenciosos. Se movieron a documento
  `tacocloud:` propio y el test de secretos lo fija.
- **La reorden evadía el outbox.** `ReorderService` creaba directo por
  `OrderApiService`, así que una compra repetida nunca generaba evento.
  Ahora registra `toCreated` tras crear.

## Cómo ejecutar

```bash
# desde la carpeta tacocloud (reactor multi-módulo)
mvn -pl tacocloud-messaging-contract test   # 5 pruebas del contrato
mvn -pl tacocloud-api test                  # 440 pruebas (78 nuevas del Lab 5)
mvn -pl tacocloud-security test             # 26 pruebas (3 nuevas del Lab 5)
mvn install -DskipTests                     # compila el reactor (17 módulos)
```

Resultado esperado: `Tests run: 440, Failures: 0, Errors: 0` en
`tacocloud-api`; `Tests run: 26` en `tacocloud-security`;
`Tests run: 5` en `tacocloud-messaging-contract`; reactor `BUILD SUCCESS`.

Desglose de las 78 nuevas en `tacocloud-api`: `OrderStatusPolicyTest`
(17), `OrderWorkflowServiceTest` (12), `OrderStatusControllerTest` (7),
`OrderApiServiceTest` (+2 de borrado con estado), `EtaCalculatorTest` (3),
`KitchenQueueServiceTest` (5), `KitchenGatewayControllerTest` (4),
`OrderEventMapperTest` (3), `MessagingTransportTest` (6),
`OutboxServiceTest` (7), `OrderPlacementServiceTest` (2),
`OrderEventConsumerTest` (10).

## Lista de verificación Definition of Done

- [x] El comportamiento cumple los criterios de aceptación de TC-25..TC-30
      (matriz + roles + `@Version` + historial; claim atómico + ETA +
      DTO seguro + solo KITCHEN; evento versionado sin sensibles;
      un bean por propiedad + fallo claro + destinos externos + sin
      secretos; orden+outbox transaccional + relay con backoff;
      dedup por eventId + retry acotado + DLQ + replay).
- [x] Las pruebas fallan antes del cambio (rutas, campos y tablas nuevas
      no existían) y pasan después; sin sleeps ni dependencias locales
      ocultas (sin Testcontainers, sin brokers reales).
- [x] No se agregan secretos, PAN/CVV reales ni logs de payload sensible
      (contraseñas de ejemplo eliminadas del yml; eventos y DTOs
      verificados sin sensibles por prueba).
- [x] Estados HTTP, errores y contratos consistentes (200/201/204,
      400 `invalid_status`/`invalid_page`, 404 `order_not_found`/
      `kitchen_queue_empty`, 409 `invalid_status_transition`/`conflict`,
      403/401 por rol, 422 heredados intactos).
- [x] La solución compone efectos reactivos; el único `subscribe()` nuevo
      vive en el relay `@Scheduled` (borde autorizado) y no hay `block()`.
- [x] Patrón, garantía, riesgo residual y hallazgos documentados (este
      documento).

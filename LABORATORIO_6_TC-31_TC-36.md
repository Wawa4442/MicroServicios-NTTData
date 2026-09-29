# Laboratorio 6 — Operación y calidad (TC-31 a TC-36)

Documentación en lenguaje natural de los 6 retos finales sobre el proyecto
`tacocloud` (reactor multi-módulo). Complementa a `LABORATORIO_1_TC-01_TC-06.md`,
`LABORATORIO_2_TC-07_TC-12.md`, `LABORATORIO_3_TC-13_TC-18.md`,
`LABORATORIO_4_TC-19_TC-24.md` y `LABORATORIO_5_TC-25_TC-30.md`.

---

## Resumen en una frase

El Laboratorio 6 no agrega sabor, agrega confianza para operar: cada petición
lleva un **correlation id que se puede seguir** de la API a la cocina sin
adivinar horarios (TC-31), Actuator deja de decir solo "UP" y **cuenta lo que
vende, lo que falla y lo que se atrasa** con tags que no revientan el backend
(TC-32), las notas en memoria pasan a ser **anuncios con id estable,
persistentes y acotados** (TC-33), un doble clic **no cobra dos veces** gracias
a la llave de idempotencia por usuario (TC-34), el contrato deja de estar solo
en la cabeza de los controladores y vive en un **`openapi.yaml` versionado**
con `/api/v1` como canónico y `/api` como alias con aviso (TC-35), y todo queda
amarrado por una **red de regresiones** que frena publishers fantasma,
contratos rotos y duplicados antes de la demo (TC-36).

---

## Cambios archivo por archivo

### 1. TC-31 — Correlation id de HTTP a evento y logs (`tacos.observability`)

- `tacocloud-api/.../tacos/observability/CorrelationIds.java` (nuevo): el
  ayudante aburrido que hacía falta. `normalize()` conserva el
  `X-Correlation-Id` cuando cumple `[A-Za-z0-9-_.:]{1,64}` y genera un UUID
  cuando falta, viene con salto de línea o mide 200 caracteres. Nunca usa el
  `orderId`: una petición puede crear varios eventos, así que la correlación
  es la petición, no la orden.
- `tacocloud-api/.../tacos/observability/CorrelationWebFilter.java` (nuevo,
  orden máximo): el borde HTTP. Lee el header, lo normaliza, lo guarda como
  atributo del exchange, lo devuelve en la respuesta y lo mete al `Context` de
  Reactor para las cadenas asíncronas. El MDC se pone para el hilo de la
  petición y se quita en `doFinally`: una petición nunca mancha la siguiente.
  No se registran bodies sensibles, solo el id.
- `tacocloud-api/.../tacos/web/api/OrderApiController.java`: los cuatro
  sitios que armaban el id a mano (`POST /orders`, `fromEmail`, `status`,
  `cancel`) ahora llaman a `CorrelationIds.normalize()`. El comportamiento es
  el mismo para el cliente bueno, pero el header malicioso/largo por fin se
  reemplaza en vez de pasearse por logs y eventos.
- `tacocloud-api/.../resources/application.yml`: el patrón de log lleva
  `%X{correlationId}` y se declara la exposición de Actuator
  (`health,info,metrics,prometheus`) con `show-details: when_authorized`.

### 2. TC-32 — Métricas y salud que explican el negocio (`tacos.observability`)

- `tacocloud-api/pom.xml`: se suma `spring-boot-starter-actuator`. Sin él no
  hay `MeterRegistry` ni health reactivo en el módulo del API.
- `tacocloud-api/.../tacos/observability/TacoBusinessMetrics.java` (nuevo):
  contadores `tacocloud.orders` (`created/failed/cancelled` por `source`),
  `tacocloud.coupons`, `tacocloud.inventory` (rechazos),
  `tacocloud.events.dlq` (por `cause`), timers `tacocloud.order.placement` y
  `tacocloud.kitchen.latency`, y el gauge `tacocloud.outbox.pending`. Los tags
  son vocabulario fijo (`api`, `workflow`, `created`...), jamás `orderId`,
  `userId` ni correlation: eso viviría en logs, no en métricas.
- `tacocloud-api/.../tacos/observability/OutboxBacklogHealthIndicator.java`
  (nuevo, bean `"outbox"`): cuenta `NEW` con `findByStatus().count()` sin
  `block()`, actualiza el gauge con el mismo número para que métrica y salud
  no se contradigan, responde UP con el pendiente y DOWN pasado 100 con el
  componente nombrado pero sin credenciales.
- `tacocloud-api/.../tacos/outbox/OrderPlacementService.java`: al crear mide
  el timer y suma `created` (y `couponApplied` si hubo cupón); al fallar suma
  `failed` y, si fue stock, `stockRejected`. La inyección es opcional por
  campo, así que los tests viejos que construyen el servicio a mano siguen
  pasando.
- `tacocloud-api/.../tacos/consumer/OrderEventConsumer.java`: cada `park()`
  en DLQ suma también `dlqParked(cause)` al negocio, además del contador
  honesto que ya tenía.

### 3. TC-33 — Adiós lista en memoria, hola anuncios (`tacos.announcements`)

- `tacocloud-api/.../tacos/announcements/OpsAnnouncement.java`,
  `OpsAnnouncementRepository.java`, `AnnouncementProperties.java`,
  `AnnouncementRequest/Response.java`, `AnnouncementService.java`,
  `AdminAnnouncementController.java` (nuevos): el documento guarda `text`,
  `severity`, `createdAt`, `expiresAt`, `createdBy` y `active`, con índice en
  expiración. El servicio valida (no vacío, tope 500, sin controles, expira en
  futuro, máximo 20 activas) con `Clock` inyectado, lista solo no expirados y
  borra por id estable. El controlador vive en `/api/admin/announcements`
  (ADMIN por la regla que ya existía): `GET` lista, `POST` crea 201,
  `DELETE /{id}` borra. Nada de `ArrayList` compartido.
- `tacocloud/.../tacos/actuator/NotesEndpoint.java` (reescrito): el endpoint
  `notes` deja de guardar en memoria con índice posicional. Ahora inyecta el
  mismo `AnnouncementService`: leer junta activos, escribir crea INFO y borrar
  pide el id. Un reinicio conserva anuncios y dos escrituras concurrentes ya
  no se pisan porque Mongo decide, no la memoria.
- `tacocloud-api/.../tacos/web/api/RestProblemHandler.java`: dos códigos
  nuevos, `404 announcement_not_found` y `422 announcement_invalid`, para que
  el tablero hable Problem como todos.

### 4. TC-34 — Idempotency-Key en creación (`tacos.idempotency`)

- `tacocloud-api/.../tacos/idempotency/IdempotencyRecord.java`,
  `IdempotencyRecordRepository.java`, `IdempotencyProperties.java` (nuevos):
  la fila recuerda `key`, `userId`, `requestHash`, `orderId`, `status`
  (`PENDING/COMPLETED/FAILED`) y `expiresAt` (TTL 24h). El id es
  `userId:key`, así que Alicia y Beto pueden usar la misma llave sin
  chocar.
- `.../IdempotencyKeys.java` (nuevo): formato `Idempotency-Key`
  `[A-Za-z0-9-_.:]{8,64}` (corto o con espacios/saltos = 400) y hash SHA-256
  sobre lo que define "la misma compra" (entrega, líneas con ids ordenados y
  cantidades, cupón en mayúsculas, referencia de pago). El JSON con otros
  espacios hashea igual; otra compra hashea distinto.
- `.../IdempotencyService.java` (nuevo): `reserve()` guarda PENDING y, si
  choca por índice único, relee: misma hash + COMPLETED = replay, misma hash
  + FAILED = se reclama, PENDING fresco = 409 en progreso, hash distinta =
  409 conflicto. `complete()`/`fail()` cierran el ciclo.
- `tacocloud-api/.../tacos/outbox/OrderPlacementService.java`: nuevo
  `placeOrder(request, caller, correlation, idempotencyKeyHeader)` que usa el
  header si viene, si no el `idempotencyKey` del body, y si no hay nada crea
  directo (compatibilidad). El replay carga la orden guardada sin reservar ni
  publicar de nuevo; el conflicto no toca inventario ni outbox.
- `tacocloud-api/.../tacos/web/api/OrderApiController.java`:
  `POST /api/orders` acepta `Idempotency-Key` opcional y lo pasa al caso de
  uso.
- `RestProblemHandler.java`: `409 idempotency_conflict` y
  `400 invalid_idempotency_key`, estables para la UI.

### 5. TC-35 — Versión y contrato (`tacos.api.version` + `openapi.yaml`)

- `tacocloud-api/.../resources/openapi.yaml` (nuevo, fuente en Git):
  describe ingredientes, tacos (búsqueda, validate, today), ratings, órdenes
  (crear con `Idempotency-Key` y `X-Correlation-Id`, quote, patch/put/delete,
  status, cancel), favoritos, historial, cocina, cupones y `ApiProblem`, con
  ejemplos y sin `password`, PAN, CVV ni `authorities`. Los schemas son DTOs,
  nunca documentos Mongo.
- `tacocloud-api/.../tacos/api/version/ApiVersionFilter.java` (nuevo):
  `/api/v1/**` se reescribe a `/api/**` antes del handler (mismo código para
  ambos prefijos, imposible olvidar una ruta en uno), marca `API-Version: v1`
  y deja `/api/**` funcionando pero con `Deprecation: true` + `Link` al
  sucesor.
- `tacocloud-api/.../tacos/api/version/OpenApiController.java` (nuevo):
  sirve el YAML en `/api/openapi.yaml` (el filtro lo expone también en
  `/api/v1/openapi.yaml`).
- `tacocloud-security/.../SecurityConfig.java`: se espejan las reglas para
  `/api/v1/**` (cocina, admin, me, orders, coupons, tacos, ingredientes) y se
  permite leer el YAML autenticado. Sin esto, `v1` caería al `authenticated()`
  genérico y la cocina podría colarse por el alias.

### 6. TC-36 — Red que frena regresiones (`tacos.integration` + CI)

- `tacocloud-api/pom.xml` (test): `testcontainers` + `mongodb:1.17.6`.
- `tacocloud-api/.../tacos/integration/RegressionGuardTest.java` (nuevo, 4):
  escanea fuentes y falla si vuelve un `.subscribe()/.block()` fuera del
  relay, si regresa `ccNumber/ccCVV` o si el contrato pierde headers o
  endurecimientos (outbox NEW, dedup por eventId, Idempotency-Key).
- `tacocloud-api/.../tacos/integration/HttpContractTest.java` (nuevo, 2):
  contratos HTTP con el runtime WebFlux real y dobles detrás: orden inválida
  es 400 `validation_error` con `instance`, y el Problem trae
  `type/title/status/code` sin `stackTrace` ni nombre de driver.
- `tacocloud-api/.../tacos/integration/MongoTestcontainersTest.java`
  (nuevo, 1 condicional): levanta `mongo:4.4`, hace ping reactivo y se
  detiene; sin Docker hace `assumeTrue` y se salta en vez de romper el
  `mvn test` del portátil. En CI con Docker sí corre.
- `.github/workflows/ci.yml` (nuevo): unit (`test` en api/security/contract),
  compilación del reactor (`install -DskipTests`) y `verify` con Docker para
  la integración.

### 7. Pruebas nuevas (47 en `tacocloud-api`, 1 condicional)

- `tacos/observability/CorrelationIdsTest` (4): genera con UUID, preserva
  válido, reemplaza malicioso/largo, nunca deriva de la orden.
- `tacos/observability/CorrelationWebFilterTest` (3): sin header recibe UUID
  en respuesta, válido se conserva, malicioso se reemplaza; MDC limpio
  siempre.
- `tacos/observability/TacoBusinessMetricsTest` (3): contadores/timers se
  mueven con escenarios reales, placement por resultado, sin tags de alta
  cardinalidad.
- `tacos/observability/OutboxBacklogHealthIndicatorTest` (3): poco = UP con
  conteo, mucho = DOWN explicado, fallo del repo = DOWN sin secretos.
- `tacos/announcements/AnnouncementServiceTest` (5) +
  `AdminAnnouncementControllerTest` (3): crea persistente, rechaza
  vacío/control, tope de activas, expirados ocultos, borrado por id correcto
  y 404 ajeno; HTTP 201 con id estable y 400 con texto en blanco.
- `tacos/idempotency/IdempotencyKeysTest` (5) +
  `IdempotencyServiceTest` (4): formato, scope por usuario, hash canónico
  (cupón case-insensitive), PENDING inicial, replay, conflicto y no colisión.
- `tacos/outbox/IdempotentPlacementTest` (3): retry secuencial una sola
  orden/evento, misma llave otro payload 409 sin crear, llave mala 400 sin
  crear.
- `tacos/api/version/ApiVersionFilterTest` (3) +
  `OpenApiContractTest` (4): reescritura v1, deprecación legacy con Link,
  admin sin marca; spec lista endpoints con 201/409, menciona headers y
  ApiProblem, sin sensibles en schemas, y el guard de forma (request sin
  total/status, response con status/total).
- `tacos/integration/RegressionGuardTest` (4) + `HttpContractTest` (2) +
  `MongoTestcontainersTest` (1, skip sin Docker).

---

## Patrones utilizados

| Reto | Patrón |
|------|--------|
| TC-31 | **Filtro de borde + objeto de valor**: el header se normaliza una vez en el filtro y viaja como atributo/contexto, no como string suelto en cada controlador. |
| TC-32 | **Métricas de negocio con tags de baja cardinalidad + health reactivo**: contar lo que vende el negocio, no ids; la salud lee sin `block()` y comparte el número con el gauge. |
| TC-33 | **Documento con id estable + servicio acotado**: la persistencia reemplaza la lista en memoria; los límites (longitud, activas, TTL) son configuración, no `if` regados. |
| TC-34 | **Registro de idempotencia con hash canónico e índice único**: la unicidad la decide la base (`userId:key`), el hash decide replay vs conflicto. |
| TC-35 | **Filtro de versión + contrato como código**: una sola implementación sirve dos prefijos; el YAML en Git es la verdad revisable, no un dump. |
| TC-36 | **Triángulo unit/integration/contract + guard de arquitectura**: lo puro con StepVerifier, lo HTTP con WebTestClient, lo real con Testcontainers, y un test que lee el código para que el error viejo no vuelva. |

## Garantías obtenidas

- **Trazabilidad sin buscar por hora:** petición sin header recibe UUID válido,
  con header válido lo conserva, con header malicioso lo reemplaza; el mismo
  id sale en respuesta, entra al evento/outbox/DLQ y aparece en logs, y el MDC
  queda limpio.
- **Operación visible:** crear/fallar/cancelar, cupón, stock y DLQ mueven
  contadores; placement y cocina miden latencia; el pendiente del outbox se ve
  en métrica y en health; ningún tag lleva ids ni secretos.
- **Tablero que sobrevive reinicios:** anuncios con id, severidad, autor de
  auditoría y expiración; tope de activas, texto acotado sin controles,
  borrado exacto y expirados que no aparecen.
- **Doble clic seguro:** misma llave + misma compra = misma orden sin
  re-reservar ni re-publicar; misma llave + otra compra = 409; llaves de
  usuarios distintos no chocan; sin llave todo sigue como antes.
- **Contrato estable y migrable:** `openapi.yaml` valida, lista cada endpoint
  con su request/response y sus headers, no filtra sensibles, y un cambio
  incompatible (total en request, status fuera de response) lo pone en rojo;
  `v1` es canónico y `legacy` avisa con `Deprecation` + `Link`.
- **Sin `block()` ni `subscribe()` nuevos** en servicios/controladores — el
  único `subscribe()` sigue en el relay `@Scheduled`, que es borde autorizado.
- **487 pruebas en `tacocloud-api`** (440 heredadas + 47 nuevas, 1 skip
  condicional sin Docker), 26 en `security`, 5 en `contract`; reactor
  `BUILD SUCCESS`.

## Riesgos residuales (defensa técnica honesta)

1. **MDC y Reactor siguen siendo dos mundos.** El filtro pone el id en MDC
   para el hilo de la petición y en `Context` para la cadena, pero un
   `publishOn`/`subscribeOn` intermedio puede perder el MDC si alguien loguea
   dentro sin propagar el contexto. Los logs del request thread llevan el id;
   el código asíncrono debe leerlo del contexto, no asumir afinidad de hilo.
2. **Health del outbox cuenta con `findByStatus`.** Con miles de pendientes
   el `count()` escanea; el umbral 100 es operativo, no científico. Si la cola
   crece a decenas de miles conviene un conteo por agregación o un índice
   parcial, igual que el backlog gauge que hoy actualiza el health.
3. **Anuncios ADMIN-only también en lectura.** Por simplicidad todo vive bajo
   `/api/admin/**`. Un operador que quiera un tablero público tendrá que abrir
   un `GET` autenticado separado; hoy leer exige ADMIN como escribir.
4. **Idempotencia sin transacción global.** La reserva PENDING se guarda antes
   del commit orden+outbox; si el proceso muere entre ambos, queda PENDING
   hasta que el TTL o el reclamo por antigüedad (5 min) lo libere. No hay
   Exactly-Once: hay Al-Menos-Una-Vez con dedup en tres capas (llave HTTP,
   reserva de inventario por key y eventId en consumidor).
5. **Versión por reescritura, no por código duplicado.** El filtro hace que
   `v1` y `legacy` compartan handler, lo cual evita drift pero impide
   evolucionarlos por separado. Cuando v2 necesite semántica distinta habrá
   que bifurcar controladores de verdad; hoy v1 es alias canónico, no fork.
6. **Broker real fuera del `mvn test` local.** Sin Docker el Testcontainers se
   salta y la mensajería se prueba con dobles + contrato + relay unitario,
   igual que en el Lab 5. La e2e contra Artemis/Rabbit/Kafka queda para CI con
   Docker, no para el portátil.

## Hallazgos de la sesión de pruebas (bugs reales que las pruebas atraparon)

- **El `OpenApiContractTest` de sensibles fallaba por su propia descripción.**
  Decía "No password..." y el `contains("password")` lo marcaba como fuga. Se
  cambió a inspeccionar solo `schemas`, no la prosa.
- **`IdempotentPlacementTest` secuencial daba 409.** El `COMPLETED` de prueba
  no llevaba `requestHash` y el `reserve()` lo comparaba contra el hash fresco.
  Fijar el hash canónico en el fixture lo volvió verde y documentó la regla.
- **`AnnouncementServiceTest` de borrado daba NPE.** El mock no sabía qué
  responder a `"missing"` y devolvía `null` en vez de `Mono.empty()`. Stub
  explícito y 404 de nuevo.
- **El filtro de correlación devolvía `Mono<String>` en el test.** El
  `WebFilterChain` exige `Mono<Void>`; el `Mono.just(id)` se cambió por
  `subscriberContext().then()`.
- **`MongoTestcontainersTest` pedía `Assumptions.abort` que el JUnit del
  proyecto no trae.** Se cambió a `assumeTrue(disponible, mensaje)`, que en
  esta versión sí existe y salta en vez de romper.

## Cómo ejecutar

```bash
# desde la carpeta tacocloud (reactor multi-módulo)
mvn -pl tacocloud-messaging-contract test   # 5 del contrato
mvn -pl tacocloud-api test                  # 487 (47 nuevas del Lab 6, 1 skip sin Docker)
mvn -pl tacocloud-security test             # 26
mvn -pl tacocloud-api,tacocloud-security,tacocloud-messaging-contract install -DskipTests  # reactor parcial
mvn -pl tacocloud -am install -DskipTests   # reactor con app (incluye Notes persistente)
```

Resultado esperado: `Tests run: 487, Failures: 0, Errors: 0, Skipped: 1` en
`tacocloud-api` (el skip es el Testcontainers sin Docker; con Docker es 0
skips); `Tests run: 26` en `security`; `BUILD SUCCESS`.

Desglose de las 47 nuevas en `tacocloud-api`: `CorrelationIdsTest` (4),
`CorrelationWebFilterTest` (3), `TacoBusinessMetricsTest` (3),
`OutboxBacklogHealthIndicatorTest` (3), `AnnouncementServiceTest` (5),
`AdminAnnouncementControllerTest` (3), `IdempotencyKeysTest` (5),
`IdempotencyServiceTest` (4), `IdempotentPlacementTest` (3),
`ApiVersionFilterTest` (3), `OpenApiContractTest` (4),
`RegressionGuardTest` (4), `HttpContractTest` (2),
`MongoTestcontainersTest` (1 condicional).

## Lista de verificación Definition of Done

- [x] El comportamiento cumple los criterios de aceptación de TC-31..TC-36
      (genera/preserva/valida correlation y lo propaga a evento/logs con MDC
      limpio; contadores/timers/gauge con tags permitidos + health degradado
      explicado; anuncios persistentes con id, límites, expiración y solo
      ADMIN; misma llave+misma compra replay, misma llave+otra 409,
      concurrentes una sola orden, scope por usuario y TTL; `/api/v1`
      canónico + `openapi.yaml` validado + alias con deprecación; suite
      unit/integration/contract con Testcontainers y CI).
- [x] Las pruebas fallan antes del cambio (rutas, headers y tablas nuevas no
      existían; el guard detecta subscribe/block, PAN y contrato roto) y pasan
      después; sin sleeps ni dependencias locales ocultas (sin Mongo/broker
      instalados; Testcontainers se salta sin Docker).
- [x] No se agregan secretos, PAN/CVV reales ni logs de payload sensible
      (contrato y eventos verificados sin sensibles por prueba; logs solo con
      correlation).
- [x] Estados HTTP, errores y contratos consistentes (201 con Location
      heredado, 200/204, 400 `validation_error`/`invalid_idempotency_key`/
      `invalid_page`, 404 `order_not_found`/`announcement_not_found`,
      409 `conflict`/`idempotency_conflict`/`invalid_status_transition`,
      422 `announcement_invalid`/`insufficient_stock`, 401/403 por rol).
- [x] La solución compone efectos reactivos; el único `subscribe()` sigue en
      el relay `@Scheduled` (borde autorizado) y no hay `block()`.
- [x] Patrón, garantía, riesgo residual y hallazgos documentados (este
      documento).

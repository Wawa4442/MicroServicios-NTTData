# Laboratorio 1 — Cazar operaciones fantasma (TC-01 a TC-06)

Documentación en lenguaje natural de los 6 retos resueltos en el módulo
`tacocloud-api`.

---

## Resumen en una frase

Corregimos seis operaciones que *parecían* funcionar pero que, vistas las
reglas de proyección Reactor, no escribían, no eliminaban, devolvían URIs rotas,
mutaban campos equivocados o construían órdenes con datos vacíos; ahora cada una
devuelve una cadena reactiva que el framework suscribe, y cada efecto se puede
verificar con una prueba que antes de este cambio habría fallado.

---

## Cambios archivo por archivo

### 1. `tacocloud-api/pom.xml`
- **Agregado:** dependencia `reactor-test` (scope `test`) para poder usar
  `StepVerifier` en las pruebas reactivas.

### 2. `IngredientController.java` (TC-01, TC-02, TC-03)
- **TC-01 — `PUT /api/ingredients/{id}`:** el método era `void`. Llamaba a
  `repo.save(ingredient)` y descartaba el `Mono` resultante. En Spring Data
  Reactive **ninguna operación toca Mongo hasta que alguien se suscribe**; como
  no había suscripción, la actualización era una *operación fantasma*.
  Ahora retorna `Mono<ResponseEntity<Ingredient>>`: valida que el ID del cuerpo
  coincida con el de la ruta (si no, **400** sin consultar nada), comprueba que
  el ingrediente exista con `findById` (si no, **404** sin guardar) y sólo
  entonces hace `save`, devolviendo **200** con la entidad actualizada.
- **TC-02 — `DELETE /api/ingredients/{id}`:** igual, era `void` y su
  `repo.deleteById(id)` jamás se suscribía. Ahora retorna
  `Mono<ResponseEntity<Void>>` que **encadena** `existsById` → `deleteById`:
  **204** cuando se elimina y **404** cuando no existe. La segunda llamada al
  mismo ID responde **404** (documentado: idempotencia de *efecto*, no igualdad
  de respuesta; tras ambas llamadas el recurso está ausente y nunca se borra dos
  veces).
- **TC-03 — `POST /api/ingredients`:** la URI `Location` estaba hardcodeada
  (`http://localhost:8080/ingredients/{id}`): puerto/scheme incorrectos en un
  proxy y ruta sin `/api` (irresoluble). Ahora se construye a partir de la
  petición real (`ServerWebExchange`) con el path `/api/ingredients` + el ID
  **asignado por persistencia**, así que respeta host, puerto, esquema y
  context path. Además se valida el cuerpo (nombre y tipo no vacíos) y un body
  inválido devuelve **400 sin guardar**.

### 3. `OrderApiController.java` (TC-04, TC-05)
- **TC-04 — `PATCH /api/orders/{orderId}`:** se corrige el defecto de copia:
  `order.setDeliveryZip(patch.getDeliveryState())` metía el *estado* en el ZIP.
  Además, el endpoint recibía el `TacoOrder` completo, permitiendo que un
  cliente tocara `id`, tarjetas, usuario y tacos. Ahora:
  - El body se lee como `JsonNode` y se valida contra una **lista blanca** de
    campos (`deliveryName/Street/City/State/Zip`). Cualquier campo prohibido
    (`ccNumber`, `tacos`, `id`, ...) se rechaza con **400** de forma explícita.
  - Se convierte a `OrderPatchRequest` (DTO con campos opcionales) y se delega
    en `OrderApiService`, que aplica la edición de entrega, valida formato de
    estado (`XX`) y ZIP (`5 dígitos`), consulta existencia (**404**), comprueba
    propiedad (**403**) y guarda **una sola vez** al final.
- **TC-05 — `PUT /api/orders/{orderId}`:** ya no ignora el `@PathVariable`.
  Un ID en el cuerpo distinto al de la ruta es **400**; un cuerpo sin ID adopta
  el de la ruta. El PUT no crea órdenes nuevas: si no existe → **404**.
  Los campos server-owned (ID, usuario, `placedAt`, datos de pago) siempre se
  preservan de la orden existente; sólo se reemplaza el contenido de entrega y
  tacos del cuerpo.
  `DELETE /api/orders/{orderId}` ya no es `void` ni usa `try/catch` síncrono
  (un `Publisher` reactivo no lanza excepciones en ese hilo): compone
  búsqueda → autorización → borrado y responde **204** (vía `.then(...)`, porque
  `deleteById` es `Mono<Void>` que no emite) o **404/403**.
- Ambas operaciones resuelven el **caller** desde `ReactiveSecurityContextHolder`
  y lo pasan al servicio; el **ownership se aplica en el servicio**, no en la
  URL (ver nota de riesgo en TC-11).

### 4. Archivos nuevos del paquete `tacos.web.api`
- `OrderPatchRequest.java` — DTO de entrada del PATCH con los únicos campos
  autorizados (opcionales).
- `OrderApiService.java` — lógica de negocio de órdenes (patch/put/delete)
  independiente del HTTP: identidad, ownership y validación. Sin
  `subscribe()`/`block()`.
- `CallerIdentity.java` — identificador del llamante (userId opcional + flag
  de ADMIN).
- `OrderNotFoundException`, `OrderAccessDeniedException`,
  `OrderPatchValidationException`, `OrderIdentityMismatchException` —
  excepciones tipadas (404, 403, 400) para mapeo HTTP consistente.

### 5. `EmailOrderService.java` (TC-06)
- **Eliminados** todos los `subscribe()` anidados y el `ArrayList` mutado desde
  callbacks (carrera: los `findById` se disparaban en paralelo, el `Mono`
  completaba con la lista aún vacía y la orden salía con ingredientes `null`).
- Una **única cadena** desde `EmailOrder` hasta `TacoOrder`:
  usuario por email (`switchIfEmpty` → error tipado) → método de pago por userId
  (`switchIfEmpty` → error tipado) → tacos con `Flux.fromIterable(...)`
  `.concatMap(toTaco)`. `concatMap` garantiza **orden de tacos e ingredientes**
  (a diferencia de `flatMap`, que intercala). `collectList` espera a que **todos**
  los ingredientes estén resueltos antes de construir la orden.
- Un ID de ingrediente desconocido produce `UnknownIngredientException`
  indicando **cuál falló**; usuario o pago ausente produce
  `EmailOrderConversionException`.
- Se protege contra listas `null` en el JSON de entrada.

### 6. Pruebas nuevas
- `IngredientControllerTest.java` — 15 pruebas: PUT 200/400/404, DELETE
  204/404 (incluida segunda llamada), POST 201 + `Location` resoluble, body
  inválido 400, y la **regresión clave**: un `AtomicBoolean` demuestra que
  `repo.save()` se ejecuta al suscribirse (con el código viejo `void` falla).
- `OrderApiControllerTest.java` — 15 pruebas: regresión ZIP/estado (cambiar ZIP
  no altera estado y viceversa), campo prohibido → 400 sin guardar, 404,
  validation 400, ownership (A intentando tocar orden de B → 403; el dueño
  puede), PUT con IDs conflictivos → 400, PUT preserva identidad/pago,
  DELETE 204/404/403.
- `EmailOrderServiceTest.java` — 5 pruebas con `StepVerifier`: caso feliz con
  varios tacos (orden e ingredientes completos), ingrediente inexistente,
  usuario ausente, método de pago ausente, y email sin tacos.
- Total: 37 pruebas verdes (antes: 2).

---

## Patrones utilizados

| Reto | Patrón |
|------|--------|
| TC-01/TC-02 | **Publisher devuelto al framework** (fail: `void` descarta el `Mono`) + existencia encadenada (compone, no bifurca). |
| TC-03 | **URIBuilder sobre la petición actual** en lugar de concatenación de strings con host hardcodeado. |
| TC-04 | **DTO de patch con lista blanca** verificada contra el JSON crudo (mass-assignment implícito → rechazo explícito). |
| TC-05 | **Servicio como caso de uso** (búsqueda → autorización → efecto) para que el ownership no dependa de filtros de URL. |
| TC-06 | **Cadena reactiva única con `concatMap` + `collectList`**: paralelismo *no* significa "terminar antes". |

## Garantías obtenidas

- **Escritura real:** todo efecto I/O que antes se "perdía" forma parte de la
  cadena retornada; el framework suscribe exactamente una vez.
- **Semántica HTTP consistente:** 200/201+Location/204/400/403/404 con contratos
  verificables y sin filtrar internos.
- **Datos protegidos:** el PATCH/PUT no puede tocar pago, usuario, ID, fechas ni
  tacos; los errores no exponen stack traces.
- **Determinismo en email:** la orden emitida siempre contiene todos los tacos e
  ingredientes solicitados, en el orden original.

## Riesgos residuales (defensa técnica honesta)

1. **`postOrderFromEmail` aún suscribe manualmente** (`order.subscribe(...)`)
   en el controlador. Está marcado con `TODO: TC-07` y corresponde al
   Laboratorio 2, donde se doblará conversión + persistencia + publicación en
   una sola secuencia (la deuda está documentada y aislada).
2. **Anonymous conserva acceso** a PATCH/PUT/DELETE de órdenes: la resolución
   de caller devuelve "anónimo" porque el baseline todavía es `permitAll`.
   El ownership se fuerza en cuanto hay usuario autenticado; el cerrojo real de
   autenticación/autorización es **TC-11** (deny-by-default). No tapamos una
   garantía de otro laboratorio con parches parciales.
3. **Eliminación física y estados:** TC-05 menciona "no borrar una orden en
   preparación"; la máquina de estados es **TC-25**. Hoy se borra físicamente;
   quedará como transición a `CANCELLED`.
4. **`Location` y proxies:** al derivar de `request.getURI()` respetamos
   scheme/host/puerto tal como llegan. Si un gateway usa encabezados
   `Forwarded`/`X-Forwarded-*`, conviene activar `ForwardedHeaderFilter`
   (queda como mejora opcional).
5. **Validación mínima en POST/PATCH** (no Bean Validation completa): el
   catálogo de `javax.validation` + `Problem Details` llega en **TC-09**.

## Cómo ejecutar

```bash
# desde la carpeta tacocloud (reactor multi-módulo)
mvn -pl tacocloud-api test
```

Resultado esperado: `Tests run: 37, Failures: 0, Errors: 0`.

## Lista de verificación Definition of Done

- [x] El comportamiento cumple los criterios de aceptación de TC-01..TC-06.
- [x] Las pruebas fallan antes del cambio (p. ej. `tc01_putSaveActuallyExecutes_onSubscription`
      con el `void` original) y pasan después; sin sleeps ni dependencias locales ocultas.
- [x] No se agregan secretos, PAN/CVV reales ni logs de payload sensible
      (se sigue usando sólo el número de tarjeta sintético del libro en datos de prueba).
- [x] Estados HTTP, errores y contratos consistentes.
- [x] La solución compone efectos reactivos; no hay `block()` ni `subscribe()`
      nuevos en servicios/controladores (el único `subscribe()` restante es el
      TODO preexistente de TC-07).
- [x] Patrón, garantía y riesgo residual documentados (este documento).
# Laboratorio 4 — Explorar, releer y repetir: catálogo buscable, taco del día, favoritos, calificaciones, historial y reordenación (TC-19 a TC-24)

Documentación en lenguaje natural de los 6 retos resueltos sobre el proyecto
`tacocloud` (reactor multi-módulo). Complementa a `LABORATORIO_1_TC-01_TC-06.md`,
`LABORATORIO_2_TC-07_TC-12.md` y `LABORATORIO_3_TC-13_TC-18.md`.

---

## Resumen en una frase

El Laboratorio 4 convierte el catálogo en algo que se **explora y se relee**: la
búsqueda filtra, ordena y pagina **en la base de datos** con lista blanca de
campos y paginación estable (TC-19), el taco del día es una recomendación
**determinista por fecha** con `Clock` inyectado que nadie puede inventar (TC-20),
los favoritos son **idempotentes y estrictamente del cliente autenticado** porque
la identidad sale del `SecurityContext` y un índice único la hace única en la base
(TC-21), cada persona califica **una vez y puede cambiar de opinión** con un top
real agregado en Mongo y no ordenado "a ojo" (TC-22), el historial es **paginado y
privado** con 404 en lugar de 403 para no confirmar la existencia de órdenes
ajenas (TC-23), y reordenar **no copia**: vuelve a ejecutar el mismo caso de uso
de una orden nueva, con precio, inventario y disponibilidad de hoy (TC-24).

---

## Cambios archivo por archivo

### 1. TC-19 — Buscar, filtrar, ordenar y paginar (`tacos.search`, `tacos.paging`)

- `GET /api/tacos?name=&ingredientId=&diet=&excludeAllergen=&spice=&page=0&size=20&sort=createdAt,desc`
  responde con `PageResponse<TacoResponse>` (solo `content`, `page`, `size`,
  `totalElements`, `totalPages`, `hasNext`): no se filtra ni se pagina en Java.
- `TacoSearchQuery` es la frontera validada: recorta y **acota el texto**
  (`maxTextLength`), convierte enums con el mensaje de valores permitidos, y
  delega el orden a `TacoSort`, que solo acepta la **lista blanca** de
  `tacos.search.sortable-fields` (`createdAt`, `name`); una dirección que no sea
  `asc`/`desc` o un campo desconocido es 400 `invalid_search`.
- `MongoTacoSearchAdapter` traduce los filtros a `Criteria` sobre
  `ReactiveMongoTemplate`. Tres decisiones de negocio explícitas: **diet** sigue
  la política *every* de TC-17 (`ingredients` `not elemMatch` con los tags que
  *incumplen*), **spice** es una **techa**, no una coincidencia exacta
  (`ingredients.spice in [...]` hasta el nivel pedido) y **name** se compila con
  `Pattern.quote` + `CASE_INSENSITIVE`, de modo que el cliente no puede introducir
  comodines ni una regex cara que el motor tenga que retroceder.
- **Estabilidad de paginación:** `TacoSearchQuery.pageable()` siempre añade la
  clave secundaria `_id` con la misma dirección, así que ninguna fila puede saltar
  ni repetirse entre páginas. `PageBounds` valida `size` (1..`max-size`) y
  `page >= 0`, y `cappedAt(max-offset)` rechaza el paging profundo
  (`page * size > maxOffset`) en lugar de dejar que Mongo salte dos millones de
  documentos. Errores: 400 `invalid_page`.
- **Índices:** `Taco` declara `taco_ingredient_created_idx` (`ingredients._id`,
  `createdAt`, `_id`), que es la consulta caliente del navegador de catálogo.
- **Ruta de UI corregida:** `RecentTacosService` pedía `/api/tacos?recent`, una
  ruta inexistente; ahora usa la ruta real a través de `ApiService`.

### 2. TC-20 — Taco del día determinista y comprobable (`tacos.recommendation`)

- `GET /api/tacos/today` devuelve el taco, la fecha y el motivo. El determinismo
  viene de tres decisiones y de nada más: la fecha sale de un **`Clock`
  inyectado** en la zona configurada (`tacos.recommendation.zone`), los candidatos
  se **ordenan por id** antes de calcular nada (`collectSortedList`), y el índice
  es `floorMod(epochDay, poolSize)`. Dos instancias con el mismo catálogo y la
  misma fecha dan el mismo id aunque Mongo devuelva las filas en otro orden
  físico.
- El pool son los tacos vendibles **revalidados con el mismo `TacoValidator` del
  flujo de órdenes**, así que un taco que un cliente dejó en el catálogo no se
  anuncia.
- **Cache por fecha con invalidación verificada:** se recuerda una sola decisión
  por fecha (la memoria no crece con el *uptime*) y, en cada lectura, el taco
  cacheado se vuelve a comprobar contra el catálogo: si dejó el menú se olvida y
  se recalcula. La cache no puede recomendar algo que la tienda no vende.
- Sin candidatos: 404 `no_taco_of_the_day`, documentado en el contrato. La razón
  es texto público y sin datos sensibles; no se persiste un taco nuevo por
  consulta.

### 3. TC-21 — Favoritos por usuario (`tacos.favorites`, documento `Favorite`)

- `GET /api/users/me/favorites`, `PUT /api/users/me/favorites/{tacoId}`,
  `DELETE /api/users/me/favorites/{tacoId}`. El `userId` **no viaja en el
  cuerpo ni en la ruta**: `CallerIdentityResolver` lo saca del contexto de
  seguridad y `FavoriteService` lo recibe como parámetro, de modo que ningún
  camino de código puede actuar sobre la lista de otro.
- El documento guarda solo `userId` + `tacoId` (+ `savedAt`), nunca el `User`
  completo: embeberlo duplicaría el hash de contraseña por favorito y filtraría
  el perfil de otra persona en cuanto la colección se exportara.
  `FavoriteRepository` además marca `exported = false` (la superficie Data REST
  es genérica y entregaría todos los favoritos a quien preguntara).
- **Idempotencia doble:** el camino feliz consulta si la fila existe, y el índice
  único compuesto `favorite_user_taco_unique` es lo que hace que dos `PUT`
  simultáneos colapsen en una fila. El segundo escritor recibe
  `DuplicateKeyException` y se responde **con la fila del primero** — un éxito,
  no un conflicto, porque pedir dos veces lo mismo no es un conflicto de estado.
  `DELETE` también es idempotente por construcción: si no hay fila, igual se
  responde "eliminado".
- **Huérfanos explícitos:** si el taco guardado desaparece, la fila **no se borra
  en cascada**; la lectura lo marca `orphaned: true` con `tacoName: null` para que
  la UI pueda decir "ya no está en el menú" en vez de que el cliente descubra la
  pérdida. El nombre se resuelve **en vivo** con una única consulta para toda la
  página (`findAllById` con ids distintos), nunca uno por fila.
- 404 `taco_not_found` al favoritar un taco inexistente: un favorito es una
  referencia a algo real.

### 4. TC-22 — Calificaciones y ranking (`tacos.rating`, documento `TacoRating`)

- `PUT /api/tacos/{tacoId}/rating {score}` y `GET /api/tacos/top?limit=10`. El
  score es 1..5 (`tacos.ratings.min-score/max-score`); fuera de rango o `limit`
  inválido es 400 `invalid_rating`.
- **Un voto por persona, cambiable:** `TacoRatingService` busca
  `(userId, tacoId)`, actualiza esa fila si existe y solo entonces guarda. La
  carrera del primer voto la resuelve el índice único
  `rating_user_taco_unique`, cuyo perdedor relee y actualiza la fila ganadora en
  lugar de fallar. Repetir `PUT` **cambia el voto y no aumenta el conteo**, y
  ningún cliente puede votar por otro porque la identidad viene del token.
- **Solo se califica lo que está en el menú:** el taco debe existir y **todos** sus
  ingredientes deben seguir disponibles, leído de la colección de ingredientes y
  no de la copia embebida en el taco (que es un snapshot del día en que se
  diseñó).
- **El top se agrega en Mongo, no N+1:** `MongoRatingRankingAdapter` ejecuta una
  `$match` + `$group` (`$avg`, `$sum`, `$min`/`$max` de score) y ordena por
  `average desc, votes desc, _id asc` **antes** de truncar al `limit`, con un
  **mínimo de votos** configurable (`min-votes`) que evita el "5.0 de una
  persona"; los nombres se pegan con una segunda consulta para toda la lista.
  El promedio se publica con escala y `RoundingMode.HALF_UP` configurables.
- **Matiz encontrado por las pruebas:** cuando la agregación vuelve vacía (el
  voto recién escrito todavía no llegó a ella), el servicio **no inventa "1
  voto"**: cuenta con `TacoRatingRepository.countByTacoId` y publica como
  promedio el score del propio cliente, que es el dato más fresco. Publicar un
  conteo inventado es un número que nadie puede defender.

### 5. TC-23 — Historial paginado y privado (`tacos.history`, `AdminOrderController`)

- `GET /api/users/me/orders?page=&size=` y `GET /api/users/me/orders/{orderId}`,
  ambos con el `userId` tomado de la autenticación.
- **El alcance se aplica en la consulta, nunca después:** `OrderRepository`
  expone `findByUser_IdOrderByPlacedAtDescIdDesc` y `countByUser_Id`. Filtrar en
  memoria después de un `findAll` sería doblemente malo: filtraría en el
  `pageSize` la existencia de órdenes ajenas y arrastraría la colección entera
  por el heap para mostrar veinte filas. Orden `placedAt desc, _id desc`: la
  clave secundaria evita que dos órdenes del mismo milisegundo se intercambien
  entre páginas.
- **Detalle seguro:** `OrderSummaryResponse`/`OrderResponse` no incluyen el
  objeto `User` ni el token de pago; el resumen lleva conteo y totales, no la
  persona que compró.
- **404 y no 403 para una orden ajena:** `OrderHistoryService.detail` filtra por
  propietario y responde `order_not_found` igual que si no existiera. Un 403
  confirmaría que la orden es real, convirtiendo el endpoint en un oráculo para
  adivinar ids; las rutas de mutación bajo `/api/orders` conservan su 403 porque
  ahí el operador ya conoce la orden.
- **El listing global es una ruta aparte:** `GET /api/admin/orders` con
  `page`, `size` y filtro opcional `userId` (filtro de operador, no una
  declaración de propiedad) vive bajo `/api/admin/**`, que la seguridad limita a
  ADMIN. No hay `?all=true` en el endpoint "me": un flag así está a un
  copy-paste de filtrar todos los pedidos en el cliente equivocado.

### 6. TC-24 — Reordenar una compra anterior (`tacos.reorder`)

- `POST /api/orders/{orderId}/reorder` con `paymentMethodId` y
  `confirmPriceChange`. La respuesta distingue el resultado:
  - **200** con `status: "QUOTE"`, `previousTotal`, `currentTotal`, `quote` y
    `differences` por línea, **sin** `order`: no se creó nada y el cliente debe
    aprobar el precio.
  - **201** con `status: "CONFIRMED"` y la `order` nueva.
- **Reordenar es ejecutar de nuevo, no clonar.** `ReorderService` convierte la
  orden histórica en un `OrderCreateRequest` normal (ids de ingredientes,
  cantidades, dirección de la orden original) y lo entrega a
  `OrderApiService.quoteOrder` y luego a `createOrder`: el mismo validador de
  Taco Physics, el mismo precio de catálogo, el mismo cupón, el mismo inventario.
  Una reordenación con su propia copia de las reglas de precio sería una segunda
  implementación que se desvía de la primera, y el "otra vez" del cliente
  significaría en silencio otra cosa.
- **Los datos se re-resuelven, no se reproducen:** el request lleva ids de
  ingredientes y se carga la versión actual de cada uno; un ingrediente retirado
  (404) o sin stock (422 `insufficient_stock`) se decide hoy.
- **El método de pago es elección del cliente:** `paymentMethodId` es obligatorio
  (400 `reorder_payment_method_required` si falta o viene vacío) y se valida
  como cualquier otro. La referencia de pago de la orden original **no se
  copia**: un token de hace meses puede estar expirado o revocado, y cobrar en
  silencio una tarjeta que el cliente no eligió no es un favor.
- **La orden original es inmutable:** la nueva orden recibe id, `placedAt` y
  reserva propios; `idempotencyKey` se reenvía al caso de uso de creación (TC-34
  groundwork), así que un reintento no duplica la compra.
- **Las diferencias se comparan por posición**, no por nombre: el replay conserva
  el orden de las líneas, y un join por nombre sería ambiguo en cuanto alguien
  pidió el mismo diseño dos veces. Solo aparecen las líneas cuyo dinero se movió,
  así que una lista vacía significa de verdad "mismo precio". El cupón **no** se
  arrastra: una promoción de hace meses puede haber caducado, y reaplicarla a
  ciegas cotizaría un total que el motor de cupones después tendría que rechazar.

---

## Patrones utilizados

| Reto  | Patrón |
|-------|--------|
| TC-19 | **Query DTO + lista blanca + criterio en base de datos**: la validación y el límite de la petición viven en un objeto de valor; el filtrado ocurre en Mongo con un índice detrás, nunca en memoria. |
| TC-20 | **Función pura del tiempo + puerto de candidatos + cache invalidable**: determinismo por `floorMod(epochDay, pool)`, fecha por `Clock` inyectado, y la cache se revalida contra la fuente. |
| TC-21 | **Identidad como parámetro + idempotencia por índice único**: la seguridad resuelve el `userId` y el índice compuesto lo convierte en un hecho de base de datos. |
| TC-22 | **Agregación en base de datos + upsert con carrera resuelta**: `$group`/`$avg` para el ranking y un índice único para que "un voto" sea un hecho, no una convención. |
| TC-23 | **Scope en la query + 404 anti-oráculo + endpoint administrativo separado**: el aislamiento se aplica donde se filtra, y la lectura global no es un flag del endpoint privado. |
| TC-24 | **Reinterpretación como comando, no clonación**: la orden histórica se traduce a un caso de uso nuevo y el precio de hoy se cotiza y confirma. |

## Garantías obtenidas

- **Nada se filtra ni se pagina en memoria:** búsqueda, favoritos e historial
  traducen page/size/filtros a la consulta; las listas secundarias (nombres de
  tacos) se resuelven con una consulta para toda la página.
- **Paginación estable:** toda lectura paginada lleva clave secundaria (`_id`,
  `savedAt`) y hay un techo de offset además de un techo de `size`.
- **Identidad verificable:** favoritos, historial, calificaciones y reordenación
  toman el `userId` del `SecurityContext`; ninguna ruta acepta un `userId` de
  cliente para actuar sobre datos de otro.
- **Idempotencia en la base, no en la aplicación:** un `PUT` repetido de favorito
  o de calificación converge en una fila incluso con peticiones concurrentes, y un
  `DELETE` repetido responde igual.
- **Voto honesto:** el ranking se agrega en Mongo con mínimo de votos, desempate
  determinista y truncado posterior al orden; la respuesta nunca publica un
  conteo inventado.
- **Privacidad sin confirmaciones:** una orden ajena responde 404 en el historial y
  una ruta "me" inexistente no llega a ningún handler.
- **Reordenar no es clonar:** la orden nueva se valida, se cotiza y se reserva con
  el mismo código que una primera orden; la original no se toca y el pago es el
  que eligió el cliente.
- **Sin `block()` ni `subscribe()` nuevos** en servicios/controladores.

## Riesgos residuales (defensa técnica honesta)

1. **Paging por offset.** Es estable porque toda consulta añade clave secundaria,
   pero bajo inserciones concurrentes una fila nueva puede desplazar una de la
   ventana. Un cursor por `(sort, _id)` lo resolvería; no se implementó porque el
   enunciado pide metadata de paginación, no un cursor, y el cambio de contrato es
   visible para el cliente.
2. **`GET /api/tacos/top` exige sesión aunque el handler no la use.** Hereda la
   regla `GET /api/tacos/**` (USER/KITCHEN), que es coherente con el catálogo
   privado del laboratorio. Está verificado en `SecurityAuthorizationTest`.
3. **La agregación de ratings puede retrasarse respecto a la escritura.** Por eso el
   fallback cuenta en vez de suponer; en producción convendría leer del primary o
   aceptar el retraso de forma explícita en el contrato.
4. **Sin Mongo real en las pruebas.** Los tests de adapter inspeccionan el
   *shape* de `Query`/`Document` de aggregation con dobles; verifican que la
   consulta es la correcta, no el comportamiento del motor sobre un índice real.
   La integración con Mongo sigue fuera del alcance automático.
5. **`tacocloud-ui` es cliente, no contrato.** Se corrigió la ruta rota de
   "recent" y se añadió el wrapper de API para las seis historias, y el módulo
   **compila y pasa lint** en los dos archivos tocados, pero no se reconstruyó
   cada pantalla (filtros, favoritos, top, historial, reordenación) — eso es
   trabajo de UI posterior.

## Cómo ejecutar

```bash
# desde la carpeta tacocloud (reactor multi-módulo)
mvn -pl tacocloud-api test        # 362 pruebas (incluye las 6 historias del Lab 4)
mvn -pl tacocloud-security test   # 23 pruebas (matriz de seguridad)
mvn install -DskipTests           # compila el reactor
```

Desglose del Lab 4 sobre `tacocloud-api`:
`TacoSearchQueryTest` (11), `MongoTacoSearchAdapterTest` (9),
`TacoSearchControllerTest` (9), `TacoOfTheDayServiceTest` (12),
`FavoriteServiceTest` (11), `FavoriteControllerTest` (12),
`TacoRatingServiceTest` (22), `MongoRatingRankingAdapterTest` (19),
`TacoRatingControllerTest` (16), `OrderHistoryServiceTest` (16),
`OrderHistoryControllerTest` (14), `AdminOrderControllerTest` (11),
`ReorderServiceTest` (33), `ReorderControllerTest` (15) y 7 casos nuevos en
`SecurityAuthorizationTest`.

Resultado esperado: `Tests run: 362, Failures: 0, Errors: 0, Skipped: 0` en
`tacocloud-api`; `Tests run: 23, Failures: 0, Errors: 0` en `tacocloud-security`;
reactor `BUILD SUCCESS`.

## Hallazgos de la sesión de pruebas (bugs reales que las pruebas atraparon)

- **`CallerIdentityResolver` devolvía 500 en lugar de 401.** Resolvía la
  identidad con `map`, y en Reactor 3.4 un `null` (anónimo) se convierte en
  `NullPointerException` → error interno, no "no autenticado". Se corrigió
  filtrando al anónimo *antes* del `map` (`requiredUserId`).
- **La ruta de la UI estaba rota desde el principio:** "recent" pedía
  `/api/tacos?recent`, que este API nunca sirvió; la franja del home quedaba
  vacía. Ahora usa la ruta real a través del wrapper `ApiService`.
- **El promedio de una agregación vacía no es un promedio.** La primera versión
  del camino de respuesta publicaba "1 voto" cuando la agregación todavía no
  había visto la escritura (replica lag). Las pruebas de servicio lo detectaron y
  se cambió por un conteo directo con `countByTacoId`, sin inventar datos.
- **`ReorderController` y los códigos de error no coincidían:** sin
  `paymentMethodId` la validación del cuerpo no disparaba y el test esperaba un
  código; un cuerpo vacío/ilegible se escapaba como 500. Se añadió el manejador de
  `ServerWebInputException` (400 `malformed_request`) y se alinearon los códigos
  (`validation_error`, `reorder_payment_method_required`).
- **Un test de ownership afirmaba algo que el código sí cumplía pero la capa de
  seguridad no.** Una ruta tipo `/api/users/{otro}/favorites` no está en el
  grant `"me"`, así que cae en `anyExchange().authenticated()`: la petición pide
  identidad primero y luego no encuentra handler (404). El test se ajustó para
  documentar el posture real (deny-by-default) en vez de prometer un 401 que la
  arquitectura no implementa.

## Lista de verificación Definition of Done

- [x] El comportamiento cumple los criterios de aceptación de TC-19..TC-24.
- [x] Las pruebas fallan antes del cambio y pasan después; sin sleeps ni
      dependencias locales ocultas.
- [x] Todo filtrado/paginación en base de datos; lista blanca de sort; índice
      detrás de la consulta caliente; determinismo del taco del día con `Clock`;
      favoritos y ratings idempotentes por índice único; alcance en la query para
      historial; reordenación que re-cotiza con el caso de uso de creación.
- [x] Identidad desde `SecurityContext`; 404 (no 403) para órdenes ajenas en el
      historial; endpoint de operador separado bajo `/api/admin/**`.
- [x] Estados HTTP y códigos estables (400 `invalid_page`/`invalid_search`/
      `invalid_rating`/`malformed_request`/`validation_error`; 404 `taco_not_found`/
      `no_taco_of_the_day`/`order_not_found`; 422 `insufficient_stock`; y
      `reorder_payment_method_required`).
- [x] Sin `block()`/`subscribe()` nuevos en servicios/controladores.
- [x] 362/362 tests OK en `tacocloud-api`, 23/23 en `tacocloud-security`; reactor
      Java `BUILD SUCCESS`; `tacocloud-ui` compila y los dos archivos tocados pasan
      `tslint`.
- [x] Patrón, garantía, riesgo residual y hallazgos documentados (este documento).

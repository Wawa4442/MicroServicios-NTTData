# Laboratorio 3 — Motor de negocio: catálogo vendible, precios de servidor, cupones, inventario, dieta y Taco Physics (TC-13 a TC-18)

Documentación en lenguaje natural de los 6 retos resueltos sobre el proyecto
`tacocloud` (reactor multi-módulo). Complementa a
`LABORATORIO_1_TC-01_TC-06.md` y `LABORATORIO_2_TC-07_TC-12.md`.

---

## Resumen en una frase

El Laboratorio 3 convierte el catálogo y la orden en **motor de negocio real**: cada
ingrediente es un recurso vendible con precio y stock (TC-13), los precios y totales
se **calculan solo en el servidor** con `BigDecimal` y moneda explícita (TC-14), el
mapa de descuentos del libro pasa a ser un **motor de cupones funcional** con
vigencia y reglas configurables (TC-15), crear una orden **reserva inventario** y
fallar/cancelar lo **libera de forma atómica y concurrente** (TC-16), el catálogo
responde preguntas reales de **vegano/gluten/alérgenos/picante** derivadas, nunca
tecleadas por el cliente (TC-17), y ningún taco inválido vuelve a colarse porque la
validación es un conjunto de **reglas componibles** (TC-18) que se ejecuta **antes**
de cotizar o guardar.

---

## Cambios archivo por archivo

### 1. TC-13 — `Ingredient` como recurso vendible (`tacocloud-domain` + `tacos.catalog`)

- La entidad `Ingredient` gana `unitPrice` (BigDecimal), `available`,
  `stockOnHand`, `reorderLevel` y `@Version`.
- Nuevo `IngredientCatalogService` y `AdminIngredientController`:
  `PATCH /api/admin/ingredients/{id}/catalog` y
  `POST /api/admin/ingredients/{id}/stock-adjustments` son operaciones explícitas,
  solo ADMIN (matriz de TC-11).
- Un ajuste que dejaría stock negativo se rechaza
  (`StockAdjustmentRejectedException` → **422**) y un conflicto de versión se
  traduce a **409** (`OptimisticLockingFailureException`). La escritura directa
  nunca puede producir stock negativo: el modelo solo cambia por operaciones
  verificadas.
- `IngredientResponse` oculta metadatos operativos innecesarios a usuarios
  normales, y expone lo que el negocio sí necesita (precio, disponibilidad y, en
  TC-17, dieta). `DevelopmentConfig` siembra valores coherentes (precios, stock,
  `available`) para que los seed data arranquen sin errores de validación.

### 2. TC-14 — Precios y cantidades del lado servidor (`tacos.pricing`)

- Política documentada en `PricingService`: `unitPrice = baseTacoFee + Σ unitPrice
  de ingredientes`, con `RoundingMode.HALF_UP` y moneda `USD`; la línea persiste
  `unitPriceAtPurchase` como **snapshot** (un cambio posterior del catálogo no
  altera órdenes históricas).
- Cantidad entre 1 y un máximo configurable (`InvalidQuantityException`).
- `subtotal`, `discount` y `total` son **server-owned**: el request no los lleva
  (`OrderCreateRequest` solo trae líneas `{taco, quantity}`). La prueba
  `orderCreateRequest_cannotSmuggleMoneyThrough` verifica que un payload con
  `subtotal`:0.01, `discount`:1000.00 y `total`:0.00 se descarta al serializar y
  que dos unidades producen el doble del subtotal de la línea.
- **Matiz de contrato encontrado en las pruebas:** `couponCode` SÍ es un campo de
  entrada legítimo (es el código que tecleó el cliente); lo que nunca llega del
  cliente es el resultado monetario. Por eso la prueba de mass-assignment ya no
  niega la presencia de `couponCode`, sino que comprueba que no sobrevive ningún
  monto.

### 3. TC-15 — Motor de cupones (`tacos.coupon`, nuevo paquete activo)

- El antecedente legacy (`DiscountCodeProps`) se reubica como `CouponConfig` +
  `Coupon` y se configura en `application.yml` (`tacos.coupons.coupons`):
  `WELCOME10` (PERCENTAGE 10%, mínimo 10.00, tope 5.00) y `FLAT5` (FIXED 5.00,
  mínimo 20.00), ambas vigentes 2000-01-01..2099-12-31.
- `CouponEngine` (con **`Clock` inyectado**, nunca `LocalDate.now()`) evalúa el
  código contra vigencia (`NOT_STARTED`/`EXPIRED`), mínimo de compra
  (`MINIMUM_NOT_MET`) y catálogo (`UNKNOWN_CODE`), o devuelve `APPLIED` con el
  descuento exacto. Todo en `BigDecimal` con escala y redondeo explícitos.
- Reglas fuertes: el **descuento jamás vuelve negativo el total** (se clampa al
  subtotal), el tope máximo limita porcentajes altos, la normalización es
  case-insensitive, **un solo cupón por orden** y el código aplicado se guarda en
  la orden.
- El catálogo nunca se enumera: `POST /api/coupons/validate` responde **siempre
  200** con el status calculado, y no existe endpoint que liste cupones. La
  decisión de aplicar cupón en create/replace es del servidor
  (`CouponNotApplicableException` → 422 `coupon_not_applicable` con el motivo).

### 4. TC-16 — Reservar y liberar inventario sin vender aire (`tacos.inventory`)

- Puertos hexagonales `StockPort` (debit/credit) y `ReservationLedger`
  (persistencia de reservas), con `InventoryService` como caso de uso y dos
  implementaciones Mongo: `MongoStockPort` y `MongoReservationLedger`.
- **Sin read-modify-write**: el débito es un `updateFirst` atómico cuyo *query*
  lleva la guarda `stockOnHand >= solicitado` (la prueba
  `tryDebit_isASingleAtomicUpdate_withStockGuardInTheQuery` inspecciona el `Query`
  y el `Update` que se envían a Mongo). Si la guarda no matchea → `false` → la
  demanda se rechaza con `InsufficientStockException` (422 `insufficient_stock`)
  sin llevar ningún saldo a negativo.
- La demanda se **agrega y ordena por id de ingrediente** (`requirementsOf`), para
  que el orden de proceso sea determinista; un fallo a mitad
  **compensa en orden inverso** exactamente lo ya debitado.
- La reserva tiene clave, estado (`RESERVED`/`CONFIRMED`/`RELEASED`) y vínculo con
  la orden: el reintento con la misma clave es **idempotente** (no vulve a
  descontar) y la liberación es una **transición vigilada** que devuelve el stock
  exactamente una vez.
- **Bug de producción encontrado por las pruebas de contrato:** el `confirm`
  original encadenaba un `flatMap` que legítimamente emite vacío al confirmar
  sobre un `switchIfEmpty(error "No reservation found")`; cualquier confirmación
  exitosa (la primera) reventaba. Se corrigió moviendo la comprobación de
  "reserva inexistente" al interior de la rama de inspección del estado retenido.
- **Decisión de negocio:** `release` está permitida desde `RESERVED` **y**
  `CONFIRMED` (solo RESERVED hacía perder stock para siempre en delete/replace);
  pero sigue siendo una transición: nunca se libera dos veces y una orden
  confirmada conserva su stock hasta una cancelación deliberada.
- Integración en flujo: `createOrder` ejecuta `validar diseño → resolver
  ingredientes → precio → cupón → reservar → guardar → confirmar`, con
  compensación en `onErrorResume`; `replaceOrder` libera la clave **anterior**
  (`releaseStaleReservation`) y `deleteOrder` libera vía `releaseForOrder`.

### 5. TC-17 — Dieta, alérgenos y picante (`tacos.classification`)

- Enums `DietaryTag`, `Allergen` y `SpiceLevel` (jamás strings libres) sobre cada
  ingrediente.
- `TacoClassificationService` **deriva** la clasificación del taco a partir de sus
  ingredientes resueltos: una etiqueta "sin X" solo es verdadera si **todos** los
  ingredientes cumplen (every-policy), los alérgenos son la **unión exacta** y el
  picante se calcula con política determinista (máximo), documentada.
- El cliente no puede falsificar etiquetas: los POST de diseño/orden solo mandan
  ids de ingredientes; la clasificación sale en `GET /api/tacos/{id}/classification`
  y en los metadatos dietarios de `IngredientResponse`/quote.
- Es **null-safe** con ingredientes legacy sin metadatos, y expone el disclaimer
  académico: la metadata no sustituye control real de contaminación cruzada.

### 6. TC-18 — Taco Physics: reglas componibles (`tacos.rules`)

- `TacoRule` (`List<RuleViolation> check(List<Ingredient>)`) + `RuleViolation` con
  **código estable** y mensaje; `TacoValidator` recibe la **colección inyectada**
  de reglas y agrega todas las violaciones (sin fail-fast).
- Reglas: `SingleBaseRule` (`SINGLE_BASE`), `IngredientCountRule` entre 2 y 12
  (`TOO_FEW_INGREDIENTS`/`TOO_MANY_INGREDIENTS`), `NoDuplicateIngredientRule`
  (`DUPLICATE_INGREDIENT`), `AvailableIngredientsRule`
  (`UNAVAILABLE_INGREDIENT`), y dos divertidas configurables:
  `GhostPepperNeedsDrinkRule` (`GHOST_PEPPER_NEEDS_DRINK`: el GHPR exige bebida) y
  `VeganNoMeatRule` (`VEGAN_NO_MEAT`), cada una inertizable desde
  `TacoPhysicsProperties` (`tacos.physics.*` en YAML: min 2, max 12, GHPR, bebidas,
  `vegan-no-meat-enabled`). No hay IDs mágicos en código: todo es configuración.
- `TacoDesignInvalidException` lleva las violaciones; `POST /api/tacos/validate`
  las expone con un solo problema 422 (`taco_design_invalid`) y **el mismo
  validador se usa en create y quote** (TC-18), y siempre **antes** de cotizar o
  reservar inventario.
- Garantía verificable: agregar una regla nueva no toca el validador central
  (prueba de extensibilidad con una regla fake) y el orden de las reglas no
  cambia el resultado (se documenta el no-fail-fast).

---

## Patrones utilizados

| Reto  | Patrón |
|-------|--------|
| TC-13 | **Recurso vendible + operaciones explícitas ADMIN**: el modelo no escapa a stock negativo; cada cambio es una operación verificada con `@Version`. |
| TC-14 | **Snapshot de precio en la línea** + moneda/escala explícitas: el histórico no se re-preció al cambiar el catálogo. |
| TC-15 | **Motor con reglas de configuración** (Strategy sobre propiedades) + `Clock` inyectado: fecha y reglas son inyectables, no cableadas. |
| TC-16 | **Puertos hexagonales + update atómico condicionado**: la atomicidad vive en el documento (guarda `stock >= x`), no en un check-then-act. |
| TC-17 | **Derivación every-policy + unión de alérgenos**: el dato no se acepta del cliente, se calcula por composición estricta. |
| TC-18 | **Colección de reglas (Strategy) con agregación sin fail-fast**: el validador solo orquesta; las reglas crecen sin tocar el núcleo. |

## Garantías obtenidas

- **Cero dinero en `double`:** todo el pricing, cupones y precios unitarios son
  `BigDecimal` con `RoundingMode` explícito y moneda definida.
- **Total server-owned:** ningún monto traído por el cliente sobrevive; las
  cantidades sí (entre 1 y el máximo).
- **Cupones deterministas:** vigencia contra el `Clock` inyectado, topping de
  descuento, clamp a subtotal, un cupón por orden y catálogo no enumerable.
- **Sin venta de aire:** dos compradores concurrentes con stock 1 no pueden
  sobrevender (guarda atómica en el query), el fallo parcial compensa, el retry es
  idempotente y la liberación es exactamente una vez. Nunca se observa stock
  negativo.
- **Etiquetas honestas:** una etiqueta positiva exige que todos los ingredientes
  cumplan; los alérgenos son la unión exacta; el picante es determinista.
- **Validación temprana:** ningún diseño inválido llega a cotizar, guardar o
  reservar; las mismas reglas se usan en create y quote; códigos de violación
  estables para la UI.
- **Sin `block()` ni `subscribe()` nuevos** en servicios/controladores: todos los
  casos de uso componen publishers reactivos.

## Riesgos residuales (defensa técnica honesta)

1. **Actualización atómica por ingrediente, no transacción de orden.** La
   garantía de "no sobrevender los diez ingredientes a la vez" existe por
   agregación ordenada + guarda atómica + compensación; Mongo no transacciona
   toda la orden. Bajo presión extrema un fallo entre el último `$inc` y el
   `save` de la reserva se recupera por compensación en `onErrorResume`, pero la
   ventana es pequeña y el costo de una transacción distribuida real no se pagó en
   el laboratorio.
2. **`confirm` después de `save` no es transaccional.** Si el proceso muere entre
   persistir la orden y confirmar la reserva, la reserva queda `RESERVED` (no
   perdida, pero tampoco `CONFIRMED`); el replay por clave idempotente lo
   reconcilia en producción al reconocer el estado. No se implementó un job
   reconciliador.
3. **`FakePaymentGateway` y backends Mongo simulados.** Los tests de contrato
   Mongo verifican el *shape* de `Query`/`Update` (y usan `UpdateResult.acknowledged`
   real del driver tras descubrir que mockear la clase abstracta rompía Mockito);
   no ejecutan un Mongo real. La prueba de integración con Mongo real sigue fuera
   del alcance automático.
4. **Catálogo de cupones en YAML.** Los códigos viven en `application.yml`: es
   aceptable para el laboratorio, pero un equipo comercial real querría una
   tabla administrable (con `activeTo` más cortos) y firma de código. La
   arquitectura del motor (interfaz + config) no bloquea ese cambio.
5. **`tacocloud-ui` es cliente, no contrato.** El reto solo exige que la UI envíe
   cantidades y no calcule totales; se verificó que el módulo Angular compila,
   pero no se tocó su lógica de carrito.

## Cómo ejecutar

```bash
# desde la carpeta tacocloud (reactor multi-módulo)
mvn -pl tacocloud-api test        # 152 pruebas (incluye las 6 suites del Lab 3)
mvn install -DskipTests           # compila los 16 módulos
```

Desglose relevante del Lab 3 sobre `tacocloud-api`:
`CouponEngineTest` (17), `CouponControllerTest` (5), `InventoryServiceTest` (10),
`MongoStockPortTest` (3), `MongoReservationLedgerTest` (4),
`TacoClassificationTest` (9), `TacoValidatorTest` (14), `OrderApiServiceTest` (5),
`ContractSerializationTest` (9) y los 3 casos web nuevos en
`OrderApiControllerTest` (36).

Resultado esperado: `Tests run: 152, Failures: 0, Errors: 0, Skipped: 0`;
reactor `BUILD SUCCESS`.

## Hallazgos de la sesión de pruebas (bugs reales que las pruebas atraparon)

- **`InventoryService.confirm` reventaba toda confirmación:** el `switchIfEmpty`
  se colocó fuera de un `flatMap` cuya rama de éxito emite vacío; la primera
  confirmación de cualquier orden fallaba con "No reservation found". Los tests de
  contrato con fakes en memoria lo reprodujeron y la corrección movió la
  comprobación de ausencia a la rama de inspección del estado.
- **Mockear la clase abstracta `UpdateResult` rompía Mockito** (`UnfinishedStubbing`
  sobre un método de clase abstracta sin declaración); la verificación de los
  contratos Mongo usa la fábrica pública real `UpdateResult.acknowledged(...)`.
- **La notación de dinero/estado en los documentos Mongo:** `$gte` y `$set` son
  documentos anidados y los enums se guardan como objetos, no como strings; las
  aserciones de contrato se ajustaron para inspeccionar `$set.status` /
  `$gte` y comparar contra `ReservationStatus.*`.

## Lista de verificación Definition of Done

- [x] El comportamiento cumple los criterios de aceptación de TC-13..TC-18.
- [x] Las pruebas fallan antes del cambio y pasan después; sin sleeps ni
      dependencias locales ocultas.
- [x] Dinero 100% `BigDecimal`; totales server-owned; cupones con `Clock`
      inyectado; inventario atómico, compensado e idempotente; clasificación
      derivada; reglas componibles usadas en create y quote antes de reservar.
- [x] Estados HTTP y códigos estables (422 `insufficient_stock`,
      `coupon_not_applicable`, `taco_design_invalid`; violaciones con códigos
      estables para la UI).
- [x] Sin `block()`/`subscribe()` nuevos en servicios/controladores.
- [x] 152/152 tests OK en `tacocloud-api`; reactor completo `BUILD SUCCESS`.
- [x] Patrón, garantía, riesgo residual y hallazgos documentados (este documento).
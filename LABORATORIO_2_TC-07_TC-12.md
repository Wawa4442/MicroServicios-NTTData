# Laboratorio 2 — Órdenes reales, contratos, errores, seguridad y pagos tokenizados (TC-07 a TC-12)

Documentación en lenguaje natural de los 6 retos resueltos sobre el proyecto
`tacocloud` (reactor multi-módulo). Complementa a
`LABORATORIO_1_TC-01_TC-06.md`.

---

## Resumen en una frase

Cerramos las deudas que el Laboratorio 1 dejó anotadas: la orden se **publica
solo después de persistir** (y una sola vez), la API deja de exponer entidades y
**jamás** devuelve datos de tarjeta, todo error sale como **Problem Details** con
un código de estado consistente, el registro de usuarios usa **bcrypt + unicidad
real**, el runtime pasa a **seguridad reactiva deny-by-default** con roles, y el
panorama de pagos se **tokeniza**: el dominio no vuelve a ver un PAN ni un CVV.

---

## Cambios archivo por archivo

### 1. `tacocloud-api` — servicios de mensajería (TC-07)

- `OrderMessagingService` y sus equivalentes (`TacoMessagingService`,
  `KitchenMessagingService`, `OrderReceiver`) exponen
  `default Mono<Void> sendOrderReactive(TacoOrder)`, de modo que enviar una orden
  es parte de la cadena reactiva y no un efecto paralelo.
- `OrderApiController.postOrder` / `postOrderFromEmail` ahora componen
  `convertir → repo.save → sendOrderReactive` en un único publisher
  (`thenReturn`), eliminando el `subscribe()` manual que quedó marcado como
  `TODO: TC-07` en el Laboratorio 1.
- Garantía verificable: si `save` falla, **no se publica**; el publisher de
  entrada (el `Mono<EmailOrder>` de la petición) se suscribe **exactamente una
  vez** (`tc07_fromEmail_coldPublisher_isSubscribedExactlyOnce`).

### 2. `tacocloud-api/tacos/api/dto` — DTOs y contrato (TC-08)

- Nuevos DTOs: `OrderCreateRequest`, `OrderResponse`, `TacoLineRequest`,
  `TacoLineResponse`, `IngredientRequest`, `IngredientResponse` (+ mappers
  `OrderMapper`, `IngredientMapper`).
- La API dejó de recibir/devolver `TacoOrder` e `Ingredient` (entidades). El
  request no tiene `id`, `placedAt`, `status`, `userId` ni campos de tarjeta:
  los campos server-owned son **inmunes al mass-assignment**
  (`orderCreateRequest_isImmuneToServerOwnedFields`).
- La respuesta no contiene `password`, `authorities`, usuario embebido ni
  datos de tarjeta (`orderResponse_neverLeaksPersistentOrSensitiveFields`).
- **Bug encontrado y corregido durante este reto:** `OrderApiService` usaba
  `Mono.just(null)` para usuario/pago ausentes; Reactor prohíbe `null` y esas
  ramas reventaban con 500. Se reescribió la resolución con `Optional`
  (`Mono<Optional<User>>`, `Mono<Optional<PaymentMethod>>`), que sí admite
  ausencia.

### 3. `tacocloud-api` — validación y Problem Details (TC-09)

- Dependencia `spring-boot-starter-validation`; `@Valid @RequestBody` en los
  controladores y constraints `@NotBlank/@Size/@Email/@Pattern` en los DTO.
- `ApiProblem` (status, type, title, detail, instance, `code`, `violations`) y
  `RestProblemHandler` (`@RestControllerAdvice`) que responde
  `application/problem+json`:

  | Excepción / caso                              | HTTP | `code`                 |
  |-----------------------------------------------|------|------------------------|
  | `WebExchangeBindException` (Bean Validation)  | 400  | `validation_error`     |
  | `OrderPatchValidationException`               | 400  | `validation_error`     |
  | `UnknownPaymentMethodException`               | 400  | `invalid_payment_method` |
  | `DuplicateKeyException`                        | 409  | `conflict`             |
  | `OrderNotFoundException`                      | 404  | `order_not_found`      |
  | `OrderAccessDeniedException`                  | 403  | `access_denied`        |
  | `UnknownIngredientException`                  | 422  | `unknown_ingredient`   |
  | `EmailOrderConversionException`               | 422  | `order_rejected`       |
  | Cualquier otra                                | 500  | `internal_error`       |

- El `500` **no** expone stack trace, excepción ni mensaje interno
  (`detail` = `"An unexpected error occurred."`). Se retiraron los
  `onErrorResume` locales de los controladores: el mapeo de errores vive en un
  solo lugar.

### 4. `tacocloud-security` — registro, passwords y unicidad (TC-10)

- `User` gana un campo `role` (por defecto `"ROLE_USER"`) que alimenta
  `getAuthorities()`, e índices únicos `@Indexed(unique = true)` en `username` y
  `email`.
- `UserRepositoryUserDetailsService` pasa a implementar
  `ReactiveUserDetailsService` (sin `.block()`).
- `SecurityConfig` reemplaza cualquier encoder en claro por
  `PasswordEncoderFactories.createDelegatingPasswordEncoder()`: lo guardado
  lleva prefijo `{bcrypt}` y la verificación sigue aceptando otros formatos
  migrados.
- `RegistrationForm` con Bean Validation y `RegistrationController` reactivo:
  comprueba `existsByUsername`/`existsByEmail` antes de guardar; un duplicado
  produce `DuplicateKeyException` (→ **409**, o gana la carrera del índice
  único) y el alta exitosa responde **303 See Other** hacia `/login`.
- `DevelopmentConfig` siembra `habuma` con `ROLE_ADMIN`.

### 5. `tacocloud-security` — autorización reactiva (TC-11)

- `@EnableWebFluxSecurity` + `SecurityWebFilterChain` **deny-by-default**
  (`anyExchange().authenticated()`), con Basic y form login y CSRF deshabilitado
  para la API. Matriz de acceso:

  | Ruta                                             | Acceso                                  |
  |--------------------------------------------------|-----------------------------------------|
  | `OPTIONS /**`, `/`, `/index.html`, `/login`, `/register/**`, estáticos, `/actuator/health` | público |
  | `/api/kitchen/**`                                | `KITCHEN`                               |
  | `/api/orders/**`                                 | `USER`, `KITCHEN`, `ADMIN` (ownership en `OrderApiService`) |
  | `GET /api/ingredients/**`                        | `USER`, `KITCHEN`, `ADMIN`              |
  | `GET /api/tacos/**`                              | `USER`, `KITCHEN`                       |
  | escritura `/api/ingredients/**`, `/api/tacos/**` | `ADMIN`                                 |
  | `/data-api/**`, `/actuator/**`                   | `ADMIN`                                 |

- **Decisión de arquitectura:** el *advice* de TC-09 (`ServerWebExchange`,
  `WebExchangeBindException`) y esta seguridad son **WebFlux**. Como
  `spring-boot-starter-data-rest` arrastraba `spring-boot-starter-web`, la app
  corría en servlet y ese contrato habría fallado en runtime. Se fija
  `spring.main.web-application-type: reactive` en `application.yml`; el legado
  Data REST (`/data-api`) queda inerte y protegido.
- Nuevo `KitchenGatewayController` (`GET /api/kitchen/orders` → `Flux<OrderResponse>`)
  como caso real que exige `ROLE_KITCHEN`.
- Pruebas del filtro por `WebFilterChainProxy`, inyectando la autenticación en el
  contexto Reactor, sin levantar servidor.

### 6. Tokenización de pagos (TC-12)

- `PaymentMethod` deja de ser "tarjeta": ahora guarda `paymentToken` (opaco),
  `brand`, `last4` y `expiration` (solo display). **No hay campos para PAN ni
  CVV.**
- `TacoOrder` pierde `ccNumber`, `ccExpiration` y `ccCVV` y gana
  `paymentMethodId` (referencia opaca al método de pago).
- Nuevo paquete `tacos.payment` (puerto hexagonal):
  - `PaymentGateway` — `Mono<TokenizedCard> tokenize(ccNumber, ccCVV, expiration)`.
  - `TokenizedCard` — resultado seguro de persistir/enviar.
  - `FakePaymentGateway` — tokenizador de desarrollo: deriva `tok_…`, marca
    (`VISA`/`MASTERCARD`/`AMEX`) y últimos 4; **no retiene estado ni el PAN**.
- `OrderMapper.applyPayment` y `EmailOrderService.buildOrder` solo copian
  `payment.getId()`; `OrderMapper.merge` preserva la referencia existente cuando
  el request no trae una nueva. `DevelopmentConfig` siembra el método de pago
  **tokenizándolo** con el gateway.
- `scripts/tc12-tokenize-migration.mongo.js`: migra bases existentes
  (convierte métodos con `ccNumber` en registros tokenizados, enlaza
  `paymentMethodId` en las órdenes y elimina los campos de tarjeta). Idempotente.

---

## Patrones utilizados

| Reto  | Patrón |
|-------|--------|
| TC-07 | **Efecto encadenado antes de publicar**: `save` precede al envío y comparte la misma suscripción. |
| TC-08 | **DTO en la frontera** (request/response) + mapper explícito: las entidades no cruzan el HTTP. |
| TC-09 | **Problem Details** centralizado en `@RestControllerAdvice`: un único mapa excepción → status/código. |
| TC-10 | **Índice único en la base + excepción tipada** (la unicidad no depende de un check-then-act). |
| TC-11 | **Deny-by-default** con roles, ownership en el caso de uso, no en la URL. |
| TC-12 | **Puerto `PaymentGateway` (hexagonal)**: el dominio solo conoce tokens, nunca la tarjeta. |

## Garantías obtenidas

- **Publicación correcta:** una orden no se publica si no se guardó, y se
  publica una sola vez.
- **Contrato estable:** ningún endpoint devuelve `password`, `authorities`,
  entidades ni datos de tarjeta; los campos server-owned son inmunes.
- **Errores consistentes:** 400/403/404/409/422/500 con cuerpo
  `application/problem+json`, sin filtrar internos.
- **Acceso cerrado por defecto:** todo requiere autenticación; los roles
  habilitan lo justo (USER/KITCHEN/ADMIN).
- **Cero PAN/CVV en el dominio:** `grep -r "ccNumber\|ccCVV\|ccExpiration"` sobre
  el código Java no devuelve coincidencias.

## Riesgos residuales (defensa técnica honesta)

1. **`FakePaymentGateway` es un doble de desarrollo.** En producción debe
   sustituirse por un proveedor real (Stripe, Braintree, …) que emita tokens
   criptográficos; hoy el token se deriva con `System.currentTimeMillis()` y
   `hashCode`, suficiente para el laboratorio pero no para cobrar de verdad.
2. **La migración no puede recuperar el PAN.** Al no existir ya el número, el
   script genera un token `migrated_…` y solo conserva `last4`/`expiration`: en un
   sistema real los clientes tendrían que volver a ingresar su tarjeta.
3. **Formulario de login.** Se habilitó `formLogin`, pero no se agregó una página
   `/login` propia: se usa la que Spring Security genera. La API (Basic) y las
   pruebas no dependen de HTML.
4. **`/data-api` queda inerte** al pasar a WebFlux: la ruta está protegida como
   ADMIN, pero el soporte Data REST deja de montarse. Es aceptable porque la API
   versionada (`/api/**`) es el contrato vigente.
5. **Pruebas de arranque completo** (`TacoCloudApplicationTests`,
   `DesignTacoControllerWebTest`) requieren un Mongo disponible y no forman parte
   de la verificación automática; la validación del reactor usa
   `mvn install -DskipTests` para no depender de infraestructura.

## Cómo ejecutar

```bash
# desde la carpeta tacocloud (reactor multi-módulo)
mvn -pl tacocloud-security test     # 14 pruebas (TC-10/TC-11)
mvn -pl tacocloud-api test          # 59 pruebas (TC-07/TC-08/TC-09/TC-12)
mvn install -DskipTests             # compila los 16 módulos
```

Resultado esperado: `Tests run: 14, Failures: 0, Errors: 0` y
`Tests run: 59, Failures: 0, Errors: 0`; reactor `BUILD SUCCESS`.

## Lista de verificación Definition of Done

- [x] El comportamiento cumple los criterios de aceptación de TC-07..TC-12.
- [x] Las pruebas fallan antes del cambio (p. ej. los 500 por `Mono.just(null)`,
      el `subscribe()` de TC-07, la autorización abierta) y pasan después; sin
      sleeps ni dependencias locales ocultas.
- [x] No se agregan secretos, PAN/CVV reales ni logs de payload sensible: los
      datos de tarjeta solo aparecen como cadenas sintéticas del libro en pruebas
      del tokenizador.
- [x] Estados HTTP, errores y contratos consistentes (Problem Details).
- [x] La solución compone efectos reactivos; no hay `block()` ni `subscribe()`
      nuevos en servicios/controladores (el único `subscribe()` es el sembrado de
      desarrollo, que es un *listener* de arranque, no una ruta de petición).
- [x] Patrón, garantía y riesgo residual documentados (este documento).

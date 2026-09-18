package tacos.web.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Date;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.fasterxml.jackson.databind.ObjectMapper;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.Ingredient;
import tacos.TacoOrder;
import tacos.User;
import tacos.api.dto.OrderMapper;
import tacos.data.IngredientRepository;
import tacos.data.OrderRepository;
import tacos.data.PaymentMethodRepository;
import tacos.data.UserRepository;
import tacos.messaging.OrderMessagingService;
import tacos.pricing.PricingProperties;
import tacos.pricing.PricingService;

public class OrderApiControllerTest {

  private static final User ALICE = alice();
  private static final User BOB = bob();

  private OrderRepository repo;
  private IngredientRepository ingredientRepo;
  private UserRepository userRepo;
  private PaymentMethodRepository paymentMethodRepo;
  private OrderMessagingService messages;
  private EmailOrderService emailService;
  private OrderApiService orderService;
  private OrderMapper orderMapper;
  private PricingService pricing;
  private WebTestClient client;

  @BeforeEach
  public void setup() {
    repo = Mockito.mock(OrderRepository.class);
    ingredientRepo = Mockito.mock(IngredientRepository.class);
    userRepo = Mockito.mock(UserRepository.class);
    paymentMethodRepo = Mockito.mock(PaymentMethodRepository.class);
    messages = Mockito.mock(OrderMessagingService.class);
    emailService = Mockito.mock(EmailOrderService.class);
    orderMapper = new OrderMapper();
    pricing = new PricingService(new PricingProperties());
    orderService = new OrderApiService(repo, ingredientRepo, userRepo, paymentMethodRepo, orderMapper, pricing);
    // Emulates the reactive default method without invoking the real one on the mock.
    when(messages.sendOrderReactive(any(TacoOrder.class))).thenAnswer(inv -> {
      messages.sendOrder(inv.getArgument(0));
      return Mono.empty();
    });
    when(ingredientRepo.<Ingredient>findById("FLTO"))
        .thenReturn(Mono.just(new Ingredient("FLTO", "Flour Tortilla", Ingredient.Type.WRAP)));
    client = WebTestClient.bindToController(
            new OrderApiController(repo, messages, emailService, orderService, orderMapper, new ObjectMapper()))
        .controllerAdvice(new RestProblemHandler())
        .build();
  }

  // =====================================================================
  // TC-04: PATCH
  // =====================================================================

  @Test
  public void tc04_patchZip_doesNotMutateState() {
    TacoOrder existing = aliceOrder("order1");
    when(repo.<String>findById("order1")).thenReturn(Mono.just(existing));
    when(repo.save(any(TacoOrder.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

    client.patch().uri("/api/orders/order1")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"deliveryZip\":\"90210\"}")
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.deliveryZip").isEqualTo("90210")
        .jsonPath("$.deliveryState").isEqualTo("TX");
  }

  @Test
  public void tc04_patchState_doesNotMutateZip() {
    TacoOrder existing = aliceOrder("order1");
    when(repo.<String>findById("order1")).thenReturn(Mono.just(existing));
    when(repo.save(any(TacoOrder.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

    client.patch().uri("/api/orders/order1")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"deliveryState\":\"CA\"}")
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.deliveryZip").isEqualTo("78701")
        .jsonPath("$.deliveryState").isEqualTo("CA");
  }

  @Test
  public void tc04_patchProhibitedField_returns400_noSave() {
    when(repo.<String>findById("order1")).thenReturn(Mono.just(aliceOrder("order1")));

    client.patch().uri("/api/orders/order1")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"ccCVV\":\"123\",\"deliveryZip\":\"90210\"}")
        .exchange()
        .expectStatus().isBadRequest();

    verify(repo, never()).save(any(TacoOrder.class));
  }

  @Test
  public void tc04_patchNonExistentOrder_returns404() {
    when(repo.<String>findById("ghost")).thenReturn(Mono.empty());

    client.patch().uri("/api/orders/ghost")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"deliveryZip\":\"90210\"}")
        .exchange()
        .expectStatus().isNotFound();
  }

  @Test
  public void tc04_patchInvalidZip_returns400() {
    when(repo.<String>findById("order1")).thenReturn(Mono.just(aliceOrder("order1")));

    client.patch().uri("/api/orders/order1")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"deliveryZip\":\"12\"}")
        .exchange()
        .expectStatus().isBadRequest();

    verify(repo, never()).save(any(TacoOrder.class));
  }

  @Test
  public void tc04_userCannotPatchForeignOrder_returns403() {
    when(repo.<String>findById("order1")).thenReturn(Mono.just(bobOrder("order1")));
    when(repo.save(any(TacoOrder.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

    WebTestClient authClient = authenticatedClient(ALICE);

    authClient.patch().uri("/api/orders/order1")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"deliveryZip\":\"90210\"}")
        .exchange()
        .expectStatus().isForbidden();

    verify(repo, never()).save(any(TacoOrder.class));
  }

  @Test
  public void tc04_ownerCanPatchOwnOrder() {
    when(repo.<String>findById("order1")).thenReturn(Mono.just(bobOrder("order1")));
    when(repo.save(any(TacoOrder.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

    WebTestClient authClient = authenticatedClient(BOB);

    authClient.patch().uri("/api/orders/order1")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"deliveryCity\":\"Dallas\"}")
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.deliveryCity").isEqualTo("Dallas");
  }

  // =====================================================================
  // TC-05: PUT (request DTO; server-owned fields are ignored)
  // =====================================================================

  @Test
  public void tc05_putServerOwnedIdIsIgnored_pathIdWins() {
    when(repo.<String>findById("order1")).thenReturn(Mono.just(aliceOrder("order1")));
    when(repo.save(any(TacoOrder.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

    client.put().uri("/api/orders/order1")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"id\":\"order2\",\"deliveryName\":\"Hacked Name\","
            + "\"deliveryStreet\":\"1 Oak\",\"deliveryCity\":\"Austin\","
            + "\"deliveryState\":\"TX\",\"deliveryZip\":\"78701\","
            + "\"tacos\":[{\"name\":\"Test Taco\",\"ingredientIds\":[\"FLTO\"]}]}")
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.id").isEqualTo("order1")
        .jsonPath("$.deliveryName").isEqualTo("Hacked Name");
  }

  @Test
  public void tc05_putExistingOrder_updatesDeliveryAndPreservesIdentityAndPayment() {
    TacoOrder existing = aliceOrder("order1");
    when(repo.<String>findById("order1")).thenReturn(Mono.just(existing));
    when(repo.save(any(TacoOrder.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

    client.put().uri("/api/orders/order1")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"deliveryName\":\"New Name\",\"deliveryStreet\":\"1 Oak\","
            + "\"deliveryCity\":\"Austin\",\"deliveryState\":\"TX\","
            + "\"deliveryZip\":\"10001\","
            + "\"tacos\":[{\"name\":\"Test Taco\",\"ingredientIds\":[\"FLTO\"]}]}")
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.id").isEqualTo("order1")
        .jsonPath("$.deliveryName").isEqualTo("New Name")
        .jsonPath("$.deliveryZip").isEqualTo("10001");

    ArgumentCaptor<TacoOrder> captor = ArgumentCaptor.forClass(TacoOrder.class);
    verify(repo).save(captor.capture());
    TacoOrder saved = captor.getValue();
    assertEquals("order1", saved.getId());
    assertEquals(existing.getPlacedAt(), saved.getPlacedAt());
    assertEquals("pm-1", saved.getPaymentMethodId(),
        "payment is preserved when the request does not reference a new method");
  }

  @Test
  public void tc05_putNonExistentOrder_returns404() {
    when(repo.<String>findById("ghost")).thenReturn(Mono.empty());

    client.put().uri("/api/orders/ghost")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"deliveryName\":\"Ghost Buster\",\"deliveryStreet\":\"1 Oak\","
            + "\"deliveryCity\":\"Austin\",\"deliveryState\":\"TX\","
            + "\"deliveryZip\":\"78701\","
            + "\"tacos\":[{\"name\":\"Test Taco\",\"ingredientIds\":[\"FLTO\"]}]}")
        .exchange()
        .expectStatus().isNotFound();
  }

  @Test
  public void tc05_putForeignOrder_byAuthenticatedUser_returns403() {
    when(repo.<String>findById("order1")).thenReturn(Mono.just(bobOrder("order1")));

    WebTestClient authClient = authenticatedClient(ALICE);

    authClient.put().uri("/api/orders/order1")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"deliveryName\":\"Alice Sneaks\",\"deliveryStreet\":\"1 Oak\","
            + "\"deliveryCity\":\"Austin\",\"deliveryState\":\"TX\","
            + "\"deliveryZip\":\"78701\","
            + "\"tacos\":[{\"name\":\"Test Taco\",\"ingredientIds\":[\"FLTO\"]}]}")
        .exchange()
        .expectStatus().isForbidden();

    verify(repo, never()).save(any(TacoOrder.class));
  }

  @Test
  public void tc05_putWithUnknownPaymentMethod_returns400() {
    when(repo.<String>findById("order1")).thenReturn(Mono.just(aliceOrder("order1")));
    when(paymentMethodRepo.findById("pm-missing")).thenReturn(Mono.empty());

    client.put().uri("/api/orders/order1")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"deliveryName\":\"New Name\",\"deliveryStreet\":\"1 Oak\","
            + "\"deliveryCity\":\"Austin\",\"deliveryState\":\"TX\","
            + "\"deliveryZip\":\"78701\","
            + "\"tacos\":[{\"name\":\"Test Taco\",\"ingredientIds\":[\"FLTO\"]}],"
            + "\"paymentMethodId\":\"pm-missing\"}")
        .exchange()
        .expectStatus().isBadRequest();

    verify(repo, never()).save(any(TacoOrder.class));
  }

  // =====================================================================
  // TC-05: DELETE
  // =====================================================================

  @Test
  public void tc05_deleteExistingOrder_returns204_andDeletes() {
    when(repo.<String>findById("order1")).thenReturn(Mono.just(aliceOrder("order1")));
    when(repo.<String>deleteById("order1")).thenReturn(Mono.empty());

    client.delete().uri("/api/orders/order1")
        .exchange()
        .expectStatus().isNoContent()
        .expectBody().isEmpty();

    verify(repo).deleteById("order1");
  }

  @Test
  public void tc05_deleteNonExistentOrder_returns404() {
    when(repo.<String>findById("ghost")).thenReturn(Mono.empty());

    client.delete().uri("/api/orders/ghost")
        .exchange()
        .expectStatus().isNotFound()
        .expectBody()
        .jsonPath("$.code").isEqualTo("order_not_found");

    verify(repo, never()).deleteById(any(String.class));
  }

  @Test
  public void tc05_userCannotDeleteForeignOrder_returns403() {
    when(repo.<String>findById("order1")).thenReturn(Mono.just(bobOrder("order1")));

    WebTestClient authClient = authenticatedClient(ALICE);

    authClient.delete().uri("/api/orders/order1")
        .exchange()
        .expectStatus().isForbidden();

    verify(repo, never()).deleteById(any(String.class));
  }

  @Test
  public void tc05_ownerCanDeleteOwnOrder() {
    when(repo.<String>findById("order1")).thenReturn(Mono.just(bobOrder("order1")));
    when(repo.<String>deleteById("order1")).thenReturn(Mono.empty());

    WebTestClient authClient = authenticatedClient(BOB);

    authClient.delete().uri("/api/orders/order1")
        .exchange()
        .expectStatus().isNoContent();

    verify(repo).deleteById("order1");
  }

  // =====================================================================
  // TC-07: single subscription that saves and then publishes exactly once
  // =====================================================================

  @Test
  public void tc07_post_orderIsSavedThenPublishedExactlyOnce() {
    when(repo.save(any(TacoOrder.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

    client.post().uri("/api/orders")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(orderJson("alice"))
        .exchange()
        .expectStatus().isCreated();

    verify(repo).save(any(TacoOrder.class));
    verify(messages, times(1)).sendOrder(any(TacoOrder.class));
  }

  @Test
  public void tc07_post_saveFailure_doesNotPublish() {
    when(repo.save(any(TacoOrder.class)))
        .thenReturn(Mono.error(new RuntimeException("db down")));

    client.post().uri("/api/orders")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(orderJson("alice"))
        .exchange()
        .expectStatus().is5xxServerError();

    verify(messages, never()).sendOrder(any(TacoOrder.class));
  }

  @Test
  public void tc07_fromEmail_orderIsSavedThenPublishedExactlyOnce() {
    when(emailService.convertEmailOrderToDomainOrder(any()))
        .thenReturn(Mono.just(order(ALICE)));
    when(repo.save(any(TacoOrder.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

    client.post().uri("/api/orders/fromEmail")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"email\":\"alice@taco.cl\",\"tacos\":[]}")
        .exchange()
        .expectStatus().isCreated();

    verify(repo).save(any(TacoOrder.class));
    verify(messages, times(1)).sendOrder(any(TacoOrder.class));
  }

  @Test
  public void tc07_fromEmail_saveFailure_doesNotPublish() {
    when(emailService.convertEmailOrderToDomainOrder(any()))
        .thenReturn(Mono.just(order(ALICE)));
    when(repo.save(any(TacoOrder.class)))
        .thenReturn(Mono.error(new RuntimeException("db down")));

    client.post().uri("/api/orders/fromEmail")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"email\":\"alice@taco.cl\",\"tacos\":[]}")
        .exchange()
        .expectStatus().is5xxServerError();

    verify(messages, never()).sendOrder(any(TacoOrder.class));
  }

  @Test
  public void tc07_fromEmail_conversionFailure_doesNotSaveNorPublish() {
    when(emailService.convertEmailOrderToDomainOrder(any()))
        .thenReturn(Mono.error(new EmailOrderConversionException("bad email")));

    client.post().uri("/api/orders/fromEmail")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"email\":\"ghost@taco.cl\",\"tacos\":[]}")
        .exchange()
        .expectStatus().isEqualTo(422);

    verify(repo, never()).save(any(TacoOrder.class));
    verify(messages, never()).sendOrder(any(TacoOrder.class));
  }

  @Test
  public void tc07_fromEmail_coldPublisher_isSubscribedExactlyOnce() {
    AtomicInteger subscriptions = new AtomicInteger();
    when(emailService.convertEmailOrderToDomainOrder(any())).thenAnswer(inv -> {
      @SuppressWarnings("unchecked")
      Mono<EmailOrder> request = inv.getArgument(0);
      return request
          .doOnSubscribe(s -> subscriptions.incrementAndGet())
          .map(email -> order(ALICE));
    });
    when(repo.save(any(TacoOrder.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

    OrderApiController controller =
        new OrderApiController(repo, messages, emailService, orderService, orderMapper, new ObjectMapper());
    EmailOrder email = new EmailOrder();

    StepVerifier.create(controller.postOrderFromEmail(Mono.just(email)))
        .expectNextCount(1)
        .verifyComplete();

    assertEquals(1, subscriptions.get(),
        "the inbound request publisher must be subscribed exactly once");
    verify(messages, times(1)).sendOrder(any(TacoOrder.class));
  }

  // =====================================================================
  // TC-08: input contract and mass assignment
  // =====================================================================

  @Test
  public void tc08_post_cannotFixServerOwnedFields() {
    when(repo.save(any(TacoOrder.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

    client.post().uri("/api/orders")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"deliveryName\":\"Alice Hacked\",\"deliveryStreet\":\"1 Oak\","
            + "\"deliveryCity\":\"Austin\",\"deliveryState\":\"TX\","
            + "\"deliveryZip\":\"78701\",\"ccNumber\":\"4111111111111111\","
            + "\"ccCVV\":\"123\",\"id\":\"order9\",\"placedAt\":1,"
            + "\"tacos\":[{\"name\":\"Test Taco\",\"ingredientIds\":[\"FLTO\"]}]}")
        .exchange()
        .expectStatus().isCreated()
        .expectBody()
        .jsonPath("$.ccNumber").doesNotExist()
        .jsonPath("$.ccCVV").doesNotExist()
        .jsonPath("$.ccExpiration").doesNotExist()
        .jsonPath("$.password").doesNotExist()
        .jsonPath("$.authorities").doesNotExist();

    ArgumentCaptor<TacoOrder> captor = ArgumentCaptor.forClass(TacoOrder.class);
    verify(repo).save(captor.capture());
    TacoOrder saved = captor.getValue();
    assertNull(saved.getId(), "the client cannot fix the order id");
    assertNull(saved.getPaymentMethodId(),
        "the client cannot inject card data through the DTO");
    assertNotEquals(1L, saved.getPlacedAt().getTime(),
        "placedAt is server-owned and never read from the body");
  }

  // =====================================================================
  // TC-14: server-side quantities and pricing
  // =====================================================================

  @Test
  public void tc14_quantityTwo_doublesLineSubtotal_andClientTotalsAreIgnored() {
    when(repo.save(any(TacoOrder.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

    client.post().uri("/api/orders")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"deliveryName\":\"Alice\",\"deliveryStreet\":\"1 Oak\","
            + "\"deliveryCity\":\"Austin\",\"deliveryState\":\"TX\","
            + "\"deliveryZip\":\"78701\","
            + "\"subtotal\":\"0.01\",\"total\":\"0.01\",\"discount\":\"0.01\","
            + "\"tacos\":[{\"name\":\"Test Taco\",\"ingredientIds\":[\"FLTO\"],"
            + "\"quantity\":2,\"unitPrice\":\"9.99\"}]}")
        .exchange()
        .expectStatus().isCreated()
        .expectBody()
        .jsonPath("$.currency").isEqualTo("USD")
        .jsonPath("$.subtotal").isEqualTo(2.00)
        .jsonPath("$.total").isEqualTo(2.00)
        .jsonPath("$.discount").isEqualTo(0)
        .jsonPath("$.tacos[0].quantity").isEqualTo(2)
        .jsonPath("$.tacos[0].unitPriceAtPurchase").isEqualTo(1.00)
        .jsonPath("$.tacos[0].subtotal").isEqualTo(2.00);
  }

  @Test
  public void tc14_quantityZero_returns400_noSave() {
    client.post().uri("/api/orders")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"deliveryName\":\"Alice\",\"deliveryStreet\":\"1 Oak\","
            + "\"deliveryCity\":\"Austin\",\"deliveryState\":\"TX\","
            + "\"deliveryZip\":\"78701\","
            + "\"tacos\":[{\"name\":\"Test Taco\",\"ingredientIds\":[\"FLTO\"],"
            + "\"quantity\":0}]}")
        .exchange()
        .expectStatus().isBadRequest();

    verify(repo, never()).save(any(TacoOrder.class));
  }

  @Test
  public void tc14_quantityAboveMaximum_returns422Problem_noSave() {
    client.post().uri("/api/orders")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"deliveryName\":\"Alice\",\"deliveryStreet\":\"1 Oak\","
            + "\"deliveryCity\":\"Austin\",\"deliveryState\":\"TX\","
            + "\"deliveryZip\":\"78701\","
            + "\"tacos\":[{\"name\":\"Test Taco\",\"ingredientIds\":[\"FLTO\"],"
            + "\"quantity\":21}]}")
        .exchange()
        .expectStatus().isEqualTo(422)
        .expectBody()
        .jsonPath("$.code").isEqualTo("invalid_quantity");

    verify(repo, never()).save(any(TacoOrder.class));
  }

  // =====================================================================
  // TC-09: Problem Details (RFC 9457) for validation and error mapping
  // =====================================================================
  @Test
  public void tc09_postInvalidBody_returns400ProblemWithViolations_noSave() {
    client.post().uri("/api/orders")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"deliveryName\":\"Alice\",\"deliveryStreet\":\"1 Oak\","
            + "\"deliveryCity\":\"Austin\",\"deliveryState\":\"Texas\","
            + "\"tacos\":[{\"name\":\"Test Taco\",\"ingredientIds\":[\"FLTO\"]}]}")
        .exchange()
        .expectStatus().isBadRequest()
        .expectHeader().contentTypeCompatibleWith("application/problem+json")
        .expectBody()
        .jsonPath("$.status").isEqualTo(400)
        .jsonPath("$.code").isEqualTo("validation_error")
        .jsonPath("$.instance").isEqualTo("/api/orders")
        .jsonPath("$.violations").isNotEmpty()
        .jsonPath("$.violations[?(@.field=='deliveryState')]").isNotEmpty()
        .jsonPath("$.violations[?(@.field=='deliveryZip')]").isNotEmpty()
        .jsonPath("$.title").isEqualTo("Validation failed");

    verify(repo, never()).save(any(TacoOrder.class));
  }

  @Test
  public void tc09_invalidStateInPost_doesNotSaveNorPublish() {
    client.post().uri("/api/orders")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"deliveryName\":\"Alice\",\"deliveryStreet\":\"1 Oak\","
            + "\"deliveryCity\":\"Austin\",\"deliveryState\":\"Texas\","
            + "\"deliveryZip\":\"78701\","
            + "\"tacos\":[{\"name\":\"Test Taco\",\"ingredientIds\":[\"FLTO\"]}]}")
        .exchange()
        .expectStatus().isBadRequest();

    verify(repo, never()).save(any(TacoOrder.class));
    verify(messages, never()).sendOrder(any(TacoOrder.class));
  }

  @Test
  public void tc09_missingResource_returns404Problem_withInstance() {
    when(repo.<String>findById("ghost")).thenReturn(Mono.empty());

    client.patch().uri("/api/orders/ghost")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"deliveryZip\":\"90210\"}")
        .exchange()
        .expectStatus().isNotFound()
        .expectBody()
        .jsonPath("$.status").isEqualTo(404)
        .jsonPath("$.code").isEqualTo("order_not_found")
        .jsonPath("$.instance").isEqualTo("/api/orders/ghost");
  }

  @Test
  public void tc09_unknownPaymentMethod_returns400Problem() {
    when(repo.<String>findById("order1")).thenReturn(Mono.just(aliceOrder("order1")));
    when(paymentMethodRepo.findById("pm-missing")).thenReturn(Mono.empty());

    client.put().uri("/api/orders/order1")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"deliveryName\":\"New Name\",\"deliveryStreet\":\"1 Oak\","
            + "\"deliveryCity\":\"Austin\",\"deliveryState\":\"TX\","
            + "\"deliveryZip\":\"78701\","
            + "\"tacos\":[{\"name\":\"Test Taco\",\"ingredientIds\":[\"FLTO\"]}],"
            + "\"paymentMethodId\":\"pm-missing\"}")
        .exchange()
        .expectStatus().isBadRequest()
        .expectBody()
        .jsonPath("$.code").isEqualTo("invalid_payment_method");
  }

  @Test
  public void tc09_foreignOrder_returns403Problem() {
    when(repo.<String>findById("order1")).thenReturn(Mono.just(bobOrder("order1")));

    WebTestClient authClient = authenticatedClient(ALICE);

    authClient.put().uri("/api/orders/order1")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"deliveryName\":\"Alice Sneaks\",\"deliveryStreet\":\"1 Oak\","
            + "\"deliveryCity\":\"Austin\",\"deliveryState\":\"TX\","
            + "\"deliveryZip\":\"78701\","
            + "\"tacos\":[{\"name\":\"Test Taco\",\"ingredientIds\":[\"FLTO\"]}]}")
        .exchange()
        .expectStatus().isForbidden()
        .expectBody()
        .jsonPath("$.code").isEqualTo("access_denied")
        .jsonPath("$.status").isEqualTo(403);
  }

  @Test
  public void tc09_duplicateKey_returns409Problem() {
    when(repo.save(any(TacoOrder.class)))
        .thenReturn(Mono.error(new DuplicateKeyException("dup key")));

    client.post().uri("/api/orders")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(orderJson("alice"))
        .exchange()
        .expectStatus().isEqualTo(409)
        .expectBody()
        .jsonPath("$.code").isEqualTo("conflict")
        .jsonPath("$.status").isEqualTo(409);

    verify(messages, never()).sendOrder(any(TacoOrder.class));
  }

  @Test
  public void tc09_internalError_returns500Problem_withoutStackTrace() {
    when(repo.save(any(TacoOrder.class)))
        .thenReturn(Mono.error(new RuntimeException("db down")));

    client.post().uri("/api/orders")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(orderJson("alice"))
        .exchange()
        .expectStatus().is5xxServerError()
        .expectBody()
        .jsonPath("$.code").isEqualTo("internal_error")
        .jsonPath("$.detail").isEqualTo("An unexpected error occurred.")
        .jsonPath("$.trace").doesNotExist()
        .jsonPath("$.exception").doesNotExist()
        .jsonPath("$.message").doesNotExist();
  }

  // =====================================================================
  // helpers
  // =====================================================================

  private WebTestClient authenticatedClient(User user) {
    UsernamePasswordAuthenticationToken auth =
        new UsernamePasswordAuthenticationToken(user, user.getPassword(), user.getAuthorities());
    return WebTestClient.bindToController(
            new OrderApiController(repo, messages, emailService, orderService, orderMapper, new ObjectMapper()))
        .controllerAdvice(new RestProblemHandler())
        .webFilter((exchange, chain) -> chain.filter(exchange)
            .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth)))
        .build();
  }

  private static User alice() {
    User user = new User("alice", "pw", "Alice", "1 Oak", "Austin", "TX",
        "78701", "555", "alice@taco.cl");
    user.setId("userA");
    return user;
  }

  private static User bob() {
    User user = new User("bob", "pw", "Bob", "2 Pine", "Dallas", "TX",
        "75201", "555", "bob@taco.cl");
    user.setId("userB");
    return user;
  }

  private static TacoOrder aliceOrder(String id) {
    return order(id, ALICE);
  }

  private static TacoOrder bobOrder(String id) {
    return order(id, BOB);
  }

  private static TacoOrder order(String id, User owner) {
    TacoOrder order = order(owner);
    order.setId(id);
    return order;
  }

  private static TacoOrder order(User owner) {
    TacoOrder order = new TacoOrder();
    order.setUser(owner);
    order.setPlacedAt(new Date());
    order.setDeliveryName(owner.getFullname());
    order.setDeliveryStreet(owner.getStreet());
    order.setDeliveryCity(owner.getCity());
    order.setDeliveryState(owner.getState());
    order.setDeliveryZip(owner.getZip());
    order.setPaymentMethodId("pm-1");
    return order;
  }

  private static String orderJson(String owner) {
    return "{\"deliveryName\":\"" + owner + "\",\"deliveryStreet\":\"1 Oak\","
        + "\"deliveryCity\":\"Austin\",\"deliveryState\":\"TX\","
        + "\"deliveryZip\":\"78701\","
        + "\"tacos\":[{\"name\":\"Test Taco\",\"ingredientIds\":[\"FLTO\"]}]}";
  }

}
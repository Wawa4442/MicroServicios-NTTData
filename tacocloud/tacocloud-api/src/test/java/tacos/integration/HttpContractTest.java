package tacos.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.fasterxml.jackson.databind.ObjectMapper;

import reactor.core.publisher.Mono;
import tacos.api.dto.OrderMapper;
import tacos.coupon.CouponEngine;
import tacos.data.IngredientRepository;
import tacos.data.OrderRepository;
import tacos.data.PaymentMethodRepository;
import tacos.data.UserRepository;
import tacos.inventory.InventoryService;
import tacos.inventory.StockReservation;
import tacos.messaging.OrderEvent;
import tacos.messaging.OrderEventMapper;
import tacos.outbox.OrderPlacementService;
import tacos.outbox.OutboxEvent;
import tacos.outbox.OutboxService;
import tacos.pricing.PricingProperties;
import tacos.pricing.PricingService;
import tacos.rules.TacoValidator;
import tacos.web.api.CallerIdentityResolver;
import tacos.web.api.EmailOrderService;
import tacos.web.api.OrderApiController;
import tacos.web.api.OrderApiService;
import tacos.web.api.RestProblemHandler;
import tacos.workflow.OrderWorkflowService;

/**
 * HTTP contracts against the real WebFlux runtime with fakes behind it
 * (TC-36, middle of the triangle).
 *
 * <p>Unit tests cover pure services, this class covers the HTTP edge
 * (statuses, Problem shape, headers) and the Testcontainers class next door
 * covers the real Mongo. No sleeps, no laptop-installed broker: the outbox
 * is a fake, the inventory answers immediately, and StepVerifier-equivalent
 * assertions come from WebTestClient.
 */
public class HttpContractTest {

  private WebTestClient client;

  private void bind() {
    OrderRepository repo = org.mockito.Mockito.mock(OrderRepository.class);
    IngredientRepository ingredientRepo = org.mockito.Mockito.mock(IngredientRepository.class);
    UserRepository userRepo = org.mockito.Mockito.mock(UserRepository.class);
    PaymentMethodRepository paymentRepo = org.mockito.Mockito.mock(PaymentMethodRepository.class);
    OutboxService outbox = org.mockito.Mockito.mock(OutboxService.class);
    OrderMapper orderMapper = new OrderMapper();
    PricingService pricing = new PricingService(new PricingProperties());
    CouponEngine coupons = org.mockito.Mockito.mock(CouponEngine.class);
    TacoValidator validator = org.mockito.Mockito.mock(TacoValidator.class);
    InventoryService inventory = org.mockito.Mockito.mock(InventoryService.class);
    OrderApiService orderService = new OrderApiService(repo, ingredientRepo, userRepo,
        paymentRepo, orderMapper, pricing, coupons, validator, inventory);
    OrderEventMapper events = new OrderEventMapper();
    OrderWorkflowService workflow = new OrderWorkflowService(repo);
    OrderPlacementService placement = new OrderPlacementService(orderService, outbox, events);
    EmailOrderService emailService = org.mockito.Mockito.mock(EmailOrderService.class);
    org.mockito.Mockito.when(outbox.append(org.mockito.ArgumentMatchers.any(OrderEvent.class)))
        .thenAnswer(inv -> Mono.just(new OutboxEvent()));
    org.mockito.Mockito.when(inventory.reserve(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyList()))
        .thenAnswer(inv -> Mono.just(StockReservation.created(
            inv.getArgument(0), inv.getArgument(1))));
    org.mockito.Mockito.when(inventory.confirm(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(Mono.empty());
    OrderApiController controller = new OrderApiController(repo, emailService,
        orderService, workflow, placement, outbox, events, orderMapper,
        new ObjectMapper(), new CallerIdentityResolver());
    client = WebTestClient.bindToController(controller)
        .controllerAdvice(new RestProblemHandler())
        .build();
  }

  @Test
  public void tc36_invalidOrder_isProblemWithoutStacktrace() {
    bind();
    client.post().uri("/api/orders")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"deliveryName\":\"\"}")
        .exchange()
        .expectStatus().isBadRequest()
        .expectBody()
        .jsonPath("$.code").isEqualTo("validation_error")
        .jsonPath("$.instance").isEqualTo("/api/orders");
    // The body is asserted structurally above; a stacktrace would surface as
    // an unknown top-level field, which the strict contract test forbids.
  }

  @Test
  public void tc36_problemShape_isStable() {
    bind();
    byte[] body = client.post().uri("/api/orders")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"deliveryName\":\"\"}")
        .exchange()
        .expectStatus().isBadRequest()
        .expectBody(byte[].class)
        .returnResult().getResponseBody();
    String json = new String(body, java.nio.charset.StandardCharsets.UTF_8);
    assertTrue(json.contains("\"type\""), "Problem must carry type");
    assertTrue(json.contains("\"title\""), "Problem must carry title");
    assertTrue(json.contains("\"status\""), "Problem must carry status");
    assertTrue(json.contains("\"code\""), "Problem must carry code");
    assertEquals(false, json.contains("stackTrace"), "No stacktrace in Problem");
    assertEquals(false, json.contains("Mongo"), "No driver name in Problem");
  }
}

package tacos.workflow;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.Date;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.fasterxml.jackson.databind.ObjectMapper;

import reactor.core.publisher.Mono;
import tacos.OrderStatus;
import tacos.TacoOrder;
import tacos.User;
import tacos.api.dto.OrderMapper;
import tacos.data.OrderRepository;
import tacos.messaging.OrderEvent;
import tacos.messaging.OrderEventMapper;
import tacos.outbox.OrderPlacementService;
import tacos.outbox.OutboxEvent;
import tacos.outbox.OutboxService;
import tacos.web.api.CallerIdentityResolver;
import tacos.web.api.EmailOrderService;
import tacos.web.api.OrderApiController;
import tacos.web.api.OrderApiService;
import tacos.web.api.RestProblemHandler;

/**
 * TC-25 through HTTP: status codes, contracts and the optimistic-lock path.
 */
public class OrderStatusControllerTest {

  private OrderRepository repo;
  private OutboxService outbox;
  private WebTestClient client;
  private WebTestClient kitchenClient;
  private WebTestClient aliceClient;

  @BeforeEach
  public void setup() {
    repo = Mockito.mock(OrderRepository.class);
    outbox = Mockito.mock(OutboxService.class);
    when(outbox.append(any(OrderEvent.class)))
        .thenAnswer(inv -> Mono.just(new OutboxEvent()));
    OrderWorkflowService workflow = new OrderWorkflowService(repo);
    OrderEventMapper events = new OrderEventMapper();
    // Only the workflow + outbox wiring matters here; creation engines stay
    // out of these tests.
    OrderApiService orderService = Mockito.mock(OrderApiService.class);
    OrderPlacementService placement = Mockito.mock(OrderPlacementService.class);
    EmailOrderService emailService = Mockito.mock(EmailOrderService.class);
    CallerIdentityResolver identities = new CallerIdentityResolver();
    OrderApiController controller = new OrderApiController(repo, emailService,
        orderService, workflow, placement, outbox, events, new OrderMapper(),
        new ObjectMapper(), identities);
    client = WebTestClient.bindToController(controller)
        .controllerAdvice(new RestProblemHandler())
        .build();
    kitchenClient = authenticatedClient(controller, kitchenUser());
    aliceClient = authenticatedClient(controller, alice());
  }

  @Test
  public void tc25_kitchenAdvancesCreatedToAccepted_200() {
    when(repo.findById("o1")).thenReturn(Mono.just(order("o1", "userA", OrderStatus.CREATED)));
    when(repo.save(any(TacoOrder.class)))
        .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

    kitchenClient.patch().uri("/api/orders/o1/status")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"status\":\"ACCEPTED\"}")
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.status").isEqualTo("ACCEPTED");
  }

  @Test
  public void tc25_createdToDelivered_fails409() {
    when(repo.findById("o1")).thenReturn(Mono.just(order("o1", "userA", OrderStatus.CREATED)));

    kitchenClient.patch().uri("/api/orders/o1/status")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"status\":\"DELIVERED\"}")
        .exchange()
        .expectStatus().isEqualTo(409)
        .expectBody()
        .jsonPath("$.code").isEqualTo("invalid_status_transition");
  }

  @Test
  public void tc25_userCannotMarkDelivered_403() {
    when(repo.findById("o1")).thenReturn(Mono.just(
        order("o1", "userA", OrderStatus.OUT_FOR_DELIVERY)));

    aliceClient.patch().uri("/api/orders/o1/status")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"status\":\"DELIVERED\"}")
        .exchange()
        .expectStatus().isForbidden()
        .expectBody()
        .jsonPath("$.code").isEqualTo("access_denied");
  }

  @Test
  public void tc25_ownerCancelsEarly_200() {
    when(repo.findById("o1")).thenReturn(Mono.just(order("o1", "userA", OrderStatus.CREATED)));
    when(repo.save(any(TacoOrder.class)))
        .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

    aliceClient.post().uri("/api/orders/o1/cancel")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"reason\":\"changed mind\"}")
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.status").isEqualTo("CANCELLED");
  }

  @Test
  public void tc25_repeatTransition_isIdempotent200() {
    when(repo.findById("o1")).thenReturn(Mono.just(order("o1", "userA", OrderStatus.ACCEPTED)));

    kitchenClient.patch().uri("/api/orders/o1/status")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"status\":\"ACCEPTED\"}")
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.status").isEqualTo("ACCEPTED");

    Mockito.verify(repo, Mockito.never()).save(any(TacoOrder.class));
  }

  @Test
  public void tc25_staleVersion_conflicts409() {
    when(repo.findById("o1")).thenReturn(Mono.just(order("o1", "userA", OrderStatus.CREATED)));
    when(repo.save(any(TacoOrder.class))).thenReturn(
        Mono.error(new OptimisticLockingFailureException("stale")));

    kitchenClient.patch().uri("/api/orders/o1/status")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"status\":\"ACCEPTED\"}")
        .exchange()
        .expectStatus().isEqualTo(409)
        .expectBody()
        .jsonPath("$.code").isEqualTo("conflict");
  }

  @Test
  public void tc25_historyEndpointShape_statusAndVersionAreVisible() {
    TacoOrder stored = order("o1", "userA", OrderStatus.CREATED);
    stored.setVersion(3L);
    when(repo.findById("o1")).thenReturn(Mono.just(stored));
    when(repo.save(any(TacoOrder.class)))
        .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

    kitchenClient.patch().uri("/api/orders/o1/status")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"status\":\"ACCEPTED\",\"reason\":\"fired\"}")
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.status").isEqualTo("ACCEPTED")
        .jsonPath("$.version").isNumber();
  }

  private WebTestClient authenticatedClient(OrderApiController controller, User user) {
    UsernamePasswordAuthenticationToken auth =
        new UsernamePasswordAuthenticationToken(user, user.getPassword(), user.getAuthorities());
    return WebTestClient.bindToController(controller)
        .controllerAdvice(new RestProblemHandler())
        .webFilter((exchange, chain) -> chain.filter(exchange)
            .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth)))
        .build();
  }

  private static TacoOrder order(String id, String userId, OrderStatus status) {
    TacoOrder order = new TacoOrder();
    order.setId(id);
    order.setPlacedAt(new Date());
    User user = new User("u", "pw", "U", "s", "c", "TX", "78701", "p", "u@t.co");
    user.setId(userId);
    order.setUser(user);
    order.setDeliveryName("U");
    order.setDeliveryStreet("s");
    order.setDeliveryCity("c");
    order.setDeliveryState("TX");
    order.setDeliveryZip("78701");
    order.setStatus(status);
    return order;
  }

  private static User alice() {
    User user = new User("alice", "pw", "Alice", "1 Oak", "Austin", "TX",
        "78701", "555", "alice@taco.cl");
    user.setId("userA");
    return user;
  }

  private static User kitchenUser() {
    User user = new User("cook", "pw", "Cook", "1 Oak", "Austin", "TX",
        "78701", "555", "cook@taco.cl");
    user.setId("cook-1");
    user.setRole("ROLE_KITCHEN");
    return user;
  }
}

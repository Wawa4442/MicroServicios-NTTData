package tacos.web.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.fasterxml.jackson.databind.ObjectMapper;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tacos.OrderStatus;
import tacos.TacoOrder;
import tacos.User;
import tacos.api.dto.PageResponse;
import tacos.data.OrderRepository;
import tacos.kitchen.EtaCalculator;
import tacos.kitchen.KitchenOrderResponse;
import tacos.kitchen.KitchenProperties;
import tacos.kitchen.KitchenQueueEmptyException;
import tacos.kitchen.KitchenQueueService;
import tacos.messaging.OrderEventMapper;
import tacos.outbox.OutboxService;
import tacos.workflow.OrderStatusChangeRequest;
import tacos.workflow.OrderWorkflowService;

/**
 * TC-26 through HTTP: queue paging, claim and the safe kitchen contract.
 */
public class KitchenGatewayControllerTest {

  private OrderRepository repo;
  private KitchenQueueService queue;
  private WebTestClient client;

  @BeforeEach
  public void setup() {
    repo = Mockito.mock(OrderRepository.class);
    ReactiveMongoTemplate mongo = Mockito.mock(ReactiveMongoTemplate.class);
    KitchenProperties props = new KitchenProperties();
    queue = new KitchenQueueService(repo, mongo, new EtaCalculator(props), props);
    OrderWorkflowService workflow = new OrderWorkflowService(repo);
    OutboxService outbox = Mockito.mock(OutboxService.class);
    KitchenGatewayController controller = new KitchenGatewayController(repo,
        queue, workflow, outbox, new OrderEventMapper(),
        new CallerIdentityResolver());
    client = WebTestClient.bindToController(controller)
        .controllerAdvice(new RestProblemHandler())
        .webFilter((exchange, chain) -> chain.filter(exchange)
            .contextWrite(ReactiveSecurityContextHolder.withAuthentication(
                new UsernamePasswordAuthenticationToken(kitchenUser(),
                    "pw", kitchenUser().getAuthorities()))))
        .build();
  }

  @Test
  public void tc26_queue_returnsPagedSafeTickets() {
    when(repo.findByStatusOrderByPlacedAtAscIdAsc(eq(OrderStatus.CREATED), any()))
        .thenReturn(Flux.just(ticket("o1"), ticket("o2")));
    when(repo.countByStatus(OrderStatus.CREATED)).thenReturn(Mono.just(2L));

    client.get().uri("/api/kitchen/queue?page=0&size=20")
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.content.length()").isEqualTo(2)
        .jsonPath("$.content[0].stationId").doesNotExist()
        .jsonPath("$.totalElements").isEqualTo(2);
  }

  @Test
  public void tc26_queueResponse_hasNoPaymentOrStreet() {
    when(repo.findByStatusOrderByPlacedAtAscIdAsc(eq(OrderStatus.CREATED), any()))
        .thenReturn(Flux.just(ticket("o1")));
    when(repo.countByStatus(OrderStatus.CREATED)).thenReturn(Mono.just(1L));

    client.get().uri("/api/kitchen/queue")
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.content[0].paymentMethodId").doesNotExist()
        .jsonPath("$.content[0].deliveryStreet").doesNotExist()
        .jsonPath("$.content[0].deliveryCity").isEqualTo("Austin");
  }

  @Test
  public void tc26_claimEmpty_returns404QueueEmpty() {
    KitchenQueueService empty = Mockito.mock(KitchenQueueService.class);
    when(empty.claimNext(any(), any(), any()))
        .thenReturn(Mono.error(new KitchenQueueEmptyException()));
    KitchenGatewayController controller = new KitchenGatewayController(repo,
        empty, new OrderWorkflowService(repo), Mockito.mock(OutboxService.class),
        new OrderEventMapper(), new CallerIdentityResolver());
    WebTestClient emptyClient = WebTestClient.bindToController(controller)
        .controllerAdvice(new RestProblemHandler())
        .build();

    emptyClient.post().uri("/api/kitchen/queue/claim")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"stationId\":\"s-1\"}")
        .exchange()
        .expectStatus().isNotFound()
        .expectBody()
        .jsonPath("$.code").isEqualTo("kitchen_queue_empty");
  }

  @Test
  public void tc26_advance_usesCentralWorkflow() {
    OrderWorkflowService workflow = Mockito.mock(OrderWorkflowService.class);
    TacoOrder saved = ticket("o1");
    saved.setStatus(OrderStatus.PREPARING);
    when(workflow.transition(eq("o1"), eq(OrderStatus.PREPARING), any(), eq("KITCHEN"), any()))
        .thenReturn(Mono.just(saved));
    OutboxService advanceOutbox = Mockito.mock(OutboxService.class);
    when(advanceOutbox.append(any())).thenAnswer(inv -> Mono.just(
        new tacos.outbox.OutboxEvent()));
    when(repo.findById("o1")).thenReturn(Mono.just(ticket("o1")));
    KitchenGatewayController controller = new KitchenGatewayController(repo,
        queue, workflow, advanceOutbox,
        new OrderEventMapper(), new CallerIdentityResolver());
    OrderStatusChangeRequest body = new OrderStatusChangeRequest();
    WebTestClient authed = WebTestClient.bindToController(controller)
        .controllerAdvice(new RestProblemHandler())
        .webFilter((exchange, chain) -> chain.filter(exchange)
            .contextWrite(ReactiveSecurityContextHolder.withAuthentication(
                new UsernamePasswordAuthenticationToken(kitchenUser(),
                    "pw", kitchenUser().getAuthorities()))))
        .build();

    authed.patch().uri("/api/kitchen/orders/o1/status")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"status\":\"PREPARING\"}")
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.status").isEqualTo("PREPARING");

    Mockito.verify(workflow, Mockito.times(1))
        .transition(eq("o1"), eq(OrderStatus.PREPARING), any(), eq("KITCHEN"), any());
  }

  private static TacoOrder ticket(String id) {
    TacoOrder order = new TacoOrder();
    order.setId(id);
    order.setPlacedAt(new Date());
    order.setDeliveryCity("Austin");
    order.setDeliveryState("TX");
    order.setDeliveryStreet("1 Oak");
    order.setPaymentMethodId("pm-secret");
    order.setStatus(OrderStatus.CREATED);
    order.setTacos(List.of());
    return order;
  }

  private static User kitchenUser() {
    User user = new User("cook", "pw", "Cook", "1 Oak", "Austin", "TX",
        "78701", "555", "cook@taco.cl");
    user.setId("cook-1");
    user.setRole("ROLE_KITCHEN");
    return user;
  }
}

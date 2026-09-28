package tacos.outbox;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.TacoOrder;
import tacos.api.dto.OrderCreateRequest;
import tacos.messaging.OrderEvent;
import tacos.messaging.OrderEventMapper;
import tacos.web.api.CallerIdentity;
import tacos.web.api.OrderApiService;

/**
 * TC-29 at the use-case level: order and outbox commit together or not at
 * all.
 */
public class OrderPlacementServiceTest {

  private OrderApiService orders;
  private OutboxService outbox;
  private OrderPlacementService placement;

  @BeforeEach
  public void setup() {
    orders = Mockito.mock(OrderApiService.class);
    outbox = Mockito.mock(OutboxService.class);
    placement = new OrderPlacementService(orders, outbox, new OrderEventMapper());
  }

  @Test
  public void tc29_failureBeforeCommit_leavesZeroOrderAndZeroOutbox() {
    OrderCreateRequest request = new OrderCreateRequest();
    when(orders.createOrder(any(), any()))
        .thenReturn(Mono.error(new RuntimeException("db down")));

    StepVerifier.create(placement.placeOrder(request, CallerIdentity.user("u1"), "c1"))
        .expectErrorMessage("db down")
        .verify();

    verify(outbox, never()).append(any(OrderEvent.class));
  }

  @Test
  public void tc29_confirmedOrder_registersNewOutboxRow() {
    OrderCreateRequest request = new OrderCreateRequest();
    TacoOrder saved = new TacoOrder();
    saved.setId("o1");
    when(orders.createOrder(any(), any())).thenReturn(Mono.just(saved));
    when(outbox.append(any(OrderEvent.class)))
        .thenAnswer(inv -> Mono.just(new OutboxEvent()));

    StepVerifier.create(placement.placeOrder(request, CallerIdentity.user("u1"), "c1"))
        .expectNextMatches(o -> "o1".equals(o.getId()))
        .verifyComplete();

    verify(outbox, Mockito.times(1)).append(any(OrderEvent.class));
  }
}

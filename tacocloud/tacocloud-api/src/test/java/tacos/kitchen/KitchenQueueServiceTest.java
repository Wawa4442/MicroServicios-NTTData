package tacos.kitchen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.OrderStatus;
import tacos.TacoOrder;
import tacos.data.OrderRepository;
import tacos.web.api.CallerIdentity;

/**
 * TC-26: queue order, atomic claim and the safe DTO.
 */
public class KitchenQueueServiceTest {

  private OrderRepository orders;
  private ReactiveMongoTemplate mongo;
  private KitchenQueueService queue;

  @BeforeEach
  public void setup() {
    orders = Mockito.mock(OrderRepository.class);
    mongo = Mockito.mock(ReactiveMongoTemplate.class);
    KitchenProperties props = new KitchenProperties();
    queue = new KitchenQueueService(orders, mongo,
        new EtaCalculator(props), props);
  }

  @Test
  public void tc26_queue_listsOldestFirst_withStablePaging() {
    TacoOrder first = ticket("o1");
    TacoOrder second = ticket("o2");
    when(orders.findByStatusOrderByPlacedAtAscIdAsc(eq(OrderStatus.CREATED), any(PageRequest.class)))
        .thenReturn(Flux.just(first, second));
    when(orders.countByStatus(OrderStatus.CREATED)).thenReturn(Mono.just(2L));

    StepVerifier.create(queue.queue(0, 20))
        .expectNextMatches(page ->
            page.getContent().size() == 2
            && "o1".equals(page.getContent().get(0).getId())
            && "o2".equals(page.getContent().get(1).getId())
            && page.getTotalElements() == 2)
        .verifyComplete();
  }

  @Test
  public void tc26_claim_isSingleConditionalWrite() {
    TacoOrder claimed = ticket("o1");
    claimed.setStatus(OrderStatus.ACCEPTED);
    claimed.setStationId("station-3");
    claimed.setCookId("cook-1");
    when(mongo.findAndModify(any(Query.class), any(Update.class), any(),
        eq(TacoOrder.class))).thenReturn(Mono.just(claimed));
    when(orders.countByStatus(OrderStatus.CREATED)).thenReturn(Mono.just(0L));

    StepVerifier.create(queue.claimNext("station-3", "cook-1",
            CallerIdentity.kitchen("cook-1")))
        .expectNextMatches(response ->
            "o1".equals(response.getId())
            && response.getStatus() == OrderStatus.ACCEPTED
            && "station-3".equals(response.getStationId()))
        .verifyComplete();

    // Exactly one conditional write: no read-then-save anywhere.
    Mockito.verify(mongo, Mockito.times(1))
        .findAndModify(any(Query.class), any(Update.class), any(),
            eq(TacoOrder.class));
    Mockito.verify(orders, Mockito.never()).save(any(TacoOrder.class));
  }

  @Test
  public void tc26_emptyQueue_is404Empty_not500() {
    when(mongo.findAndModify(any(Query.class), any(Update.class), any(),
        eq(TacoOrder.class))).thenReturn(Mono.empty());

    StepVerifier.create(queue.claimNext(null, null,
            CallerIdentity.kitchen("cook-1")))
        .expectError(KitchenQueueEmptyException.class)
        .verify();
  }

  @Test
  public void tc26_safeDto_hasNoSensitiveData() {
    TacoOrder order = ticket("o1");
    order.setDeliveryStreet("1 Secret St");
    order.setDeliveryZip("78701");
    order.setPaymentMethodId("pm-secret");
    KitchenOrderResponse response = KitchenOrderResponse.of(order, 7);

    String json = response.toString();
    assertFalse(json.contains("pm-secret"));
    assertFalse(json.contains("1 Secret St"));
    assertEquals("Austin", response.getDeliveryCity());
    assertEquals("TX", response.getDeliveryState());
    assertTrue(response.getEstimatedPrepMinutes() >= 0);
  }

  @Test
  public void tc26_twoStations_claimDistinctOrders() {
    // Two sequential claims hit findAndModify twice; Mongo decides the
    // winner atomically, so the service never returns the same ticket
    // twice from one queue position.
    TacoOrder first = ticket("o1");
    first.setStatus(OrderStatus.ACCEPTED);
    TacoOrder second = ticket("o2");
    second.setStatus(OrderStatus.ACCEPTED);
    when(mongo.findAndModify(any(Query.class), any(Update.class), any(),
        eq(TacoOrder.class)))
        .thenReturn(Mono.just(first))
        .thenReturn(Mono.just(second));
    when(orders.countByStatus(OrderStatus.CREATED)).thenReturn(Mono.just(1L));

    StepVerifier.create(queue.claimNext("s-1", "c-1", CallerIdentity.kitchen("c-1")))
        .expectNextMatches(r -> "o1".equals(r.getId()))
        .verifyComplete();
    StepVerifier.create(queue.claimNext("s-2", "c-2", CallerIdentity.kitchen("c-2")))
        .expectNextMatches(r -> "o2".equals(r.getId()))
        .verifyComplete();
  }

  private static TacoOrder ticket(String id) {
    TacoOrder order = new TacoOrder();
    order.setId(id);
    order.setPlacedAt(new Date());
    order.setDeliveryCity("Austin");
    order.setDeliveryState("TX");
    order.setStatus(OrderStatus.CREATED);
    order.setTacos(List.of());
    return order;
  }
}

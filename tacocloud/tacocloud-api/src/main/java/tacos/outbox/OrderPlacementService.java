package tacos.outbox;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import reactor.core.publisher.Mono;
import tacos.TacoOrder;
import tacos.api.dto.OrderCreateRequest;
import tacos.messaging.OrderEvent;
import tacos.messaging.OrderEventMapper;
import tacos.web.api.CallerIdentity;
import tacos.web.api.OrderApiService;

/**
 * The only way to place an order that the kitchen will reliably see (TC-29).
 *
 * <p>Creation and outbox registration commit in the same local transaction:
 * a failure before the commit leaves zero order and zero outbox, and a
 * confirmed order always has a {@code NEW} row for the relay. The HTTP layer
 * answers after the local commit; the broker delivery happens afterwards in
 * {@link OutboxRelay}, at least once, deduplicated by the consumer (TC-30).
 */
@Service
public class OrderPlacementService {

  private final OrderApiService orders;
  private final OutboxService outbox;
  private final OrderEventMapper events;

  public OrderPlacementService(OrderApiService orders, OutboxService outbox,
                               OrderEventMapper events) {
    this.orders = orders;
    this.outbox = outbox;
    this.events = events;
  }

  @Transactional
  public Mono<TacoOrder> placeOrder(OrderCreateRequest request, CallerIdentity caller,
                                    String correlationId) {
    return orders.createOrder(request, caller)
        .flatMap(saved -> {
          OrderEvent event = events.toCreated(saved, correlationId);
          return outbox.append(event).thenReturn(saved);
        });
  }
}

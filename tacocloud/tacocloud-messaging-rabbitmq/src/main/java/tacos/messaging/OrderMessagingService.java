package tacos.messaging;

import reactor.core.publisher.Mono;
import tacos.TacoOrder;

public interface OrderMessagingService {

  void sendOrder(TacoOrder order);

  /**
   * Reactive-shape subscription used by the API controllers so publishing can
   * participate in the same reactive chain as persistence (TC-07). Temporary
   * debt: the durable outbox mechanism (TC-29) replaces this later.
   */
  default Mono<Void> sendOrderReactive(TacoOrder order) {
    sendOrder(order);
    return Mono.empty();
  }

}

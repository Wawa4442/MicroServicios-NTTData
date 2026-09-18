package tacos.messaging;

import reactor.core.publisher.Mono;
import tacos.TacoOrder;

public interface OrderMessagingService {

  void sendOrder(TacoOrder order);

  /**
   * Reactive-shape subscription used by the API controllers so publishing can
   * participate in the same reactive chain as persistence (TC-07). The
   * synchronous {@code void sendOrder} implementations are wrapped for now;
   * this is temporary debt: the real durable, asynchronous publication belongs
   * to the outbox mechanism (TC-29).
   */
  default Mono<Void> sendOrderReactive(TacoOrder order) {
    sendOrder(order);
    return Mono.empty();
  }

}

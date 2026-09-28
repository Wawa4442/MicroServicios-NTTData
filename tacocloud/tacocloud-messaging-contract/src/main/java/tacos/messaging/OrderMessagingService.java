package tacos.messaging;

import reactor.core.publisher.Mono;

/**
 * The single port every transport implements (TC-27/TC-28).
 *
 * <p>One interface, four adapters. The application code only knows this
 * type; which bean is active is decided by
 * {@code tacocloud.messaging.transport}, never by editing a POM. The payload
 * is the versioned {@link OrderEvent}: no Mongo entity, no payment data and
 * no user object ever crosses the broker.
 */
public interface OrderMessagingService {

  /**
   * Publishes one versioned event. Implementations must be non-blocking in
   * the reactive sense: blocking JMS/Rabbit calls are deferred onto
   * {@code boundedElastic}, the Kafka future is adapted, never blocked on.
   */
  Mono<Void> sendEvent(OrderEvent event);
}

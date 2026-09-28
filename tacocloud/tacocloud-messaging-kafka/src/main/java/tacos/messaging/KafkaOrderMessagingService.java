package tacos.messaging;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Mono;

/**
 * Kafka adapter (TC-27/TC-28). The {@code ListenableFuture} of the template
 * is adapted without blocking; the topic is configuration, not a literal.
 */
@Service
@ConditionalOnProperty(name = "tacocloud.messaging.transport", havingValue = "kafka")
public class KafkaOrderMessagingService
                                  implements OrderMessagingService {

  private final KafkaTemplate<String, OrderEvent> kafka;
  private final String topic;

  public KafkaOrderMessagingService(KafkaTemplate<String, OrderEvent> kafka,
      @Value("${tacocloud.messaging.kafka.topic:tacocloud.orders.topic}") String topic) {
    this.kafka = kafka;
    this.topic = topic;
  }

  @Override
  public Mono<Void> sendEvent(OrderEvent event) {
    String key = event.getPayload() == null ? event.getEventId()
        : event.getPayload().getOrderId();
    return Mono.fromFuture(() -> kafka.send(topic, key, event).completable())
        .then();
  }

}

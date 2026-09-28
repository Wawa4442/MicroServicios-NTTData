package tacos.messaging;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * JMS adapter (TC-27/TC-28). Carries the versioned {@link OrderEvent}, never
 * a Mongo entity. The blocking {@code JmsTemplate} call is deferred onto
 * {@code boundedElastic} so the event loop never blocks; the destination
 * comes from configuration, not from a hardcoded string.
 */
@Service
@ConditionalOnProperty(name = "tacocloud.messaging.transport", havingValue = "jms")
public class JmsOrderMessagingService implements OrderMessagingService {

  private final JmsTemplate jms;
  private final String destination;

  public JmsOrderMessagingService(JmsTemplate jms,
      @Value("${tacocloud.messaging.jms.destination:tacocloud.order.queue}") String destination) {
    this.jms = jms;
    this.destination = destination;
  }

  @Override
  public Mono<Void> sendEvent(OrderEvent event) {
    return Mono.fromRunnable(() ->
        jms.convertAndSend(destination, event, message -> {
          message.setStringProperty("X_ORDER_SOURCE", "WEB");
          message.setStringProperty("X_EVENT_ID", event.getEventId());
          message.setStringProperty("X_EVENT_TYPE", String.valueOf(event.getEventType()));
          message.setStringProperty("X_EVENT_VERSION", event.getVersion());
          if (event.getCorrelationId() != null) {
            message.setStringProperty("X_CORRELATION_ID", event.getCorrelationId());
          }
          return message;
        }))
        .subscribeOn(Schedulers.boundedElastic())
        .then();
  }

}

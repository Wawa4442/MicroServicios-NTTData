package tacos.messaging;

import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * RabbitMQ adapter (TC-27/TC-28). Publishes the versioned {@link OrderEvent}
 * with tracing headers; the blocking template call is deferred onto
 * {@code boundedElastic}.
 */
@Service
@ConditionalOnProperty(name = "tacocloud.messaging.transport", havingValue = "rabbit")
public class RabbitOrderMessagingService
       implements OrderMessagingService {

  private final RabbitTemplate rabbit;
  private final String destination;

  public RabbitOrderMessagingService(RabbitTemplate rabbit,
      @Value("${tacocloud.messaging.rabbit.destination:tacocloud.order.queue}") String destination) {
    this.rabbit = rabbit;
    this.destination = destination;
  }

  @Override
  public Mono<Void> sendEvent(OrderEvent event) {
    return Mono.fromRunnable(() ->
        rabbit.convertAndSend(destination, event,
            (MessagePostProcessor) message -> {
              message.getMessageProperties().setHeader("X_EVENT_ID", event.getEventId());
              message.getMessageProperties().setHeader("X_EVENT_TYPE",
                  String.valueOf(event.getEventType()));
              message.getMessageProperties().setHeader("X_EVENT_VERSION", event.getVersion());
              message.getMessageProperties().setHeader("X_ORDER_SOURCE", "WEB");
              if (event.getCorrelationId() != null) {
                message.getMessageProperties().setHeader("X_CORRELATION_ID",
                    event.getCorrelationId());
              }
              return message;
            }))
        .subscribeOn(Schedulers.boundedElastic())
        .then();
  }

}

package tacos.messaging;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * Development and test transport (TC-28): logs the versioned event instead
 * of calling a broker. Active by default when no transport is configured;
 * production must set an explicit transport so a missing property never
 * silently discards orders.
 */
@Service
@ConditionalOnProperty(name = "tacocloud.messaging.transport",
    havingValue = "noop", matchIfMissing = true)
@Slf4j
public class NoOpOrderMessagingService
       implements OrderMessagingService {

  @Override
  public Mono<Void> sendEvent(OrderEvent event) {
    return Mono.fromRunnable(() ->
        log.info("Sending order event {} {} for order {}",
            event.getEventType(), event.getEventId(),
            event.getPayload() == null ? "?" : event.getPayload().getOrderId()));
  }

}

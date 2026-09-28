package tacos.consumer;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Retry and DLQ tuning of the kitchen consumer (TC-30).
 *
 * <p>Only transient failures retry (optimistic-lock collisions, timeouts,
 * broker hiccups), with a bounded count and exponential backoff. Permanent
 * failures (unknown version, unknown event type, illegal transition) go
 * straight to the dead-letter collection: retrying them would only burn CPU
 * in a loop.
 */
@Component
@ConfigurationProperties(prefix = "tacocloud.consumer")
public class ConsumerProperties {

  private int maxRetries = 5;
  private Duration baseBackoff = Duration.ofSeconds(2);
  private String destination = "tacocloud.order.queue";
  private String deadLetterDestination = "tacocloud.order.dlq";

  public int getMaxRetries() {
    return maxRetries;
  }

  public void setMaxRetries(int maxRetries) {
    this.maxRetries = maxRetries;
  }

  public Duration getBaseBackoff() {
    return baseBackoff;
  }

  public void setBaseBackoff(Duration baseBackoff) {
    this.baseBackoff = baseBackoff;
  }

  public String getDestination() {
    return destination;
  }

  public void setDestination(String destination) {
    this.destination = destination;
  }

  public String getDeadLetterDestination() {
    return deadLetterDestination;
  }

  public void setDeadLetterDestination(String deadLetterDestination) {
    this.deadLetterDestination = deadLetterDestination;
  }
}

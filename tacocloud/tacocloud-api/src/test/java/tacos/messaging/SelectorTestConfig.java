package tacos.messaging;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import reactor.core.publisher.Mono;

/**
 * Broker-free stand-in for the four transport adapters (TC-28 test only).
 *
 * <p>Each bean carries the same condition its real adapter has, so the
 * context runner proves the selection logic: exactly one publisher per valid
 * value, none usable on garbage. No broker connection is ever opened.
 */
@TestConfiguration
@EnableConfigurationProperties(MessagingTransportProperties.class)
public class SelectorTestConfig {

  @Bean("transport-noop")
  @ConditionalOnProperty(name = "tacocloud.messaging.transport",
      havingValue = "noop", matchIfMissing = true)
  public OrderMessagingService noop() {
    return event -> Mono.empty();
  }

  @Bean("transport-jms")
  @ConditionalOnProperty(name = "tacocloud.messaging.transport", havingValue = "jms")
  public OrderMessagingService jms() {
    return event -> Mono.empty();
  }

  @Bean("transport-rabbit")
  @ConditionalOnProperty(name = "tacocloud.messaging.transport", havingValue = "rabbit")
  public OrderMessagingService rabbit() {
    return event -> Mono.empty();
  }

  @Bean("transport-kafka")
  @ConditionalOnProperty(name = "tacocloud.messaging.transport", havingValue = "kafka")
  public OrderMessagingService kafka() {
    return event -> Mono.empty();
  }

  @Bean
  public MessagingTransportValidator transportValidator(
      MessagingTransportProperties props) {
    return new MessagingTransportValidator(props, "");
  }
}

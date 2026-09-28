package tacos.messaging;

import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ converter (TC-27/TC-28). Distinct configuration class per
 * transport: see {@link JmsMessagingConfig} for why the shared
 * {@code MessagingConfig} name had to go.
 */
@Configuration
@ConditionalOnProperty(name = "tacocloud.messaging.transport", havingValue = "rabbit")
public class RabbitMessagingConfig {

  @Bean
  public Jackson2JsonMessageConverter rabbitOrderEventConverter() {
    return new Jackson2JsonMessageConverter();
  }

}

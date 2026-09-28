package tacos.messaging;

import java.util.HashMap;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jms.support.converter.MappingJackson2MessageConverter;

/**
 * JMS converter for the versioned event (TC-27/TC-28).
 *
 * <p>Renamed from the colliding {@code tacos.messaging.MessagingConfig} that
 * JMS and RabbitMQ both declared: two classes with the same fully-qualified
 * name cannot share a classpath, so runtime broker selection was impossible
 * until each adapter owned a distinct configuration class behind its own
 * transport condition.
 */
@Configuration
@ConditionalOnProperty(name = "tacocloud.messaging.transport", havingValue = "jms")
public class JmsMessagingConfig {

  @Bean
  public MappingJackson2MessageConverter jmsOrderEventConverter() {
    MappingJackson2MessageConverter messageConverter =
                            new MappingJackson2MessageConverter();
    messageConverter.setTypeIdPropertyName("_typeId");

    Map<String, Class<?>> typeIdMappings = new HashMap<String, Class<?>>();
    typeIdMappings.put("orderEvent", OrderEvent.class);
    messageConverter.setTypeIdMappings(typeIdMappings);

    return messageConverter;
  }

}

package tacos.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * TC-28: one property decides the transport; anything else fails fast.
 */
public class MessagingTransportTest {

  private ApplicationContextRunner runner() {
    return new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(
            tacos.messaging.SelectorTestConfig.class));
  }

  @Test
  public void tc28_eachValidValue_createsExactlyOneBean() {
    for (String transport : new String[]{"noop", "jms", "rabbit", "kafka"}) {
      runner().withPropertyValues("tacocloud.messaging.transport=" + transport)
          .run(context -> {
            assertEquals(1, context.getBeansOfType(OrderMessagingService.class).size(),
                "transport " + transport + " must expose exactly one publisher");
            assertTrue(context.containsBean("transport-" + transport));
          });
    }
  }

  @Test
  public void tc28_noopIsTheDevDefault() {
    runner().run(context ->
        assertEquals(1, context.getBeansOfType(OrderMessagingService.class).size()));
  }

  @Test
  public void tc28_unknownValue_failsWithClearMessage() {
    runner().withPropertyValues("tacocloud.messaging.transport=pigeon")
        .run(context -> {
          assertTrue(context.getStartupFailure() != null,
              "an unknown transport must fail startup");
          assertTrue(context.getStartupFailure().getMessage()
              .contains("Unknown messaging transport"));
        });
  }

  @Test
  public void tc28_noopIsRefusedInProduction() {
    MessagingTransportProperties props = new MessagingTransportProperties();
    props.setTransport("noop");
    assertThrows(IllegalStateException.class,
        () -> new MessagingTransportValidator(props, "production").afterPropertiesSet());
  }

  @Test
  public void tc28_explicitTransportIsFineInProduction() throws Exception {
    MessagingTransportProperties props = new MessagingTransportProperties();
    props.setTransport("rabbit");
    new MessagingTransportValidator(props, "production").afterPropertiesSet();
  }

  @Test
  public void tc28_noHardcodedSecretsInNewConfig() throws IOException {
    // The new messaging/outbox/consumer keys must come from the environment;
    // only the historical Artemis example may still show a shape, never a
    // real password for the new paths.
    Path yml = Path.of("src/main/resources/application.yml");
    String text = Files.readString(yml);
    assertFalse(text.contains("letm31n"), "old example password must go");
    assertFalse(text.contains("l3tm31n"), "old production password must go");
    assertTrue(text.contains("${ARTEMIS_PASSWORD:}"));
    assertTrue(text.contains("tacocloud:"));
    assertTrue(text.contains("transport: ${TACOCLOUD_MESSAGING_TRANSPORT:noop}"));
  }
}

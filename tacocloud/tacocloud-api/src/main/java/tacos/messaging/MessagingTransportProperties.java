package tacos.messaging;

import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Runtime transport selection (TC-28).
 *
 * <p>{@code tacocloud.messaging.transport} is {@code noop}, {@code jms},
 * {@code rabbit} or {@code kafka}. Destinations, hosts and credentials are
 * externalized: nothing is hardcoded in the adapters, and no secret lives in
 * Git (see {@code application.yml}, which reads them from the environment).
 * An unknown value fails fast at startup with a clear message instead of
 * silently falling back to another broker.
 */
@Component
@ConfigurationProperties(prefix = "tacocloud.messaging")
public class MessagingTransportProperties {

  private static final Set<String> VALID =
      Set.of("noop", "jms", "rabbit", "kafka");

  /**
   * Active transport. Defaults to {@code noop} for dev/test only; production
   * deployments must set it explicitly (see {@link MessagingTransportValidator}).
   */
  private String transport = "noop";

  public String getTransport() {
    return transport;
  }

  public void setTransport(String transport) {
    this.transport = transport;
  }

  public boolean isValid() {
    return transport != null && VALID.contains(transport.trim().toLowerCase());
  }

  public String normalized() {
    return transport == null ? "" : transport.trim().toLowerCase();
  }
}

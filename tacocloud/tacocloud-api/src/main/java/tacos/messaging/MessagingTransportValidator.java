package tacos.messaging;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Fails the startup when the transport value is unknown or when production
 * would silently run on {@code noop} (TC-28).
 *
 * <p>A typo like {@code tacocloud.messaging.transport=rabbt} must never boot
 * into a quiet black hole. Likewise, {@code noop} only discards events in
 * dev/test: if the {@code production} profile is active without an explicit
 * transport, the application refuses to start and tells the operator what to
 * set.
 */
@Component
public class MessagingTransportValidator implements InitializingBean {

  private final MessagingTransportProperties props;
  private final String activeProfiles;

  public MessagingTransportValidator(MessagingTransportProperties props,
      @Value("${spring.profiles.active:}") String activeProfiles) {
    this.props = props;
    this.activeProfiles = activeProfiles == null ? "" : activeProfiles;
  }

  @Override
  public void afterPropertiesSet() {
    if (!props.isValid()) {
      throw new IllegalStateException(
          "Unknown messaging transport '" + props.getTransport()
          + "'. Expected one of noop|jms|rabbit|kafka "
          + "(tacocloud.messaging.transport).");
    }
    boolean production = activeProfiles.toLowerCase().contains("production");
    if (production && props.normalized().equals("noop")) {
      throw new IllegalStateException(
          "tacocloud.messaging.transport=noop is only allowed for dev/test. "
          + "Set it explicitly to jms|rabbit|kafka for production.");
    }
  }
}

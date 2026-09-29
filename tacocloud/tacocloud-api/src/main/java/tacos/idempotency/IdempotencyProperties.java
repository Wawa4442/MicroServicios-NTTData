package tacos.idempotency;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Retention for idempotency records (TC-34).
 */
@Component
@ConfigurationProperties(prefix = "tacocloud.idempotency")
public class IdempotencyProperties {

  private Duration ttl = Duration.ofHours(24);

  public Duration getTtl() {
    return ttl;
  }

  public void setTtl(Duration ttl) {
    this.ttl = ttl;
  }
}

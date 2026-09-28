package tacos.outbox;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Tuning of the reliable publisher (TC-29). Retries back off exponentially
 * and give up visibly instead of looping forever.
 */
@Component
@ConfigurationProperties(prefix = "tacocloud.outbox")
public class OutboxProperties {

  private boolean enabled = true;
  private int batchSize = 20;
  private Duration pollInterval = Duration.ofSeconds(5);
  private int maxAttempts = 8;
  private Duration baseBackoff = Duration.ofSeconds(5);

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public int getBatchSize() {
    return batchSize;
  }

  public void setBatchSize(int batchSize) {
    this.batchSize = batchSize;
  }

  public Duration getPollInterval() {
    return pollInterval;
  }

  public void setPollInterval(Duration pollInterval) {
    this.pollInterval = pollInterval;
  }

  public int getMaxAttempts() {
    return maxAttempts;
  }

  public void setMaxAttempts(int maxAttempts) {
    this.maxAttempts = maxAttempts;
  }

  public Duration getBaseBackoff() {
    return baseBackoff;
  }

  public void setBaseBackoff(Duration baseBackoff) {
    this.baseBackoff = baseBackoff;
  }

  public Duration backoffForAttempt(int attempt) {
    long factor = 1L << Math.min(Math.max(0, attempt), 10);
    return baseBackoff.multipliedBy(factor);
  }
}

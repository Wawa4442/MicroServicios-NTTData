package tacos.announcements;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Bounds that keep announcements an ops tool, not a chat (TC-33).
 */
@Component
@ConfigurationProperties(prefix = "tacocloud.announcements")
public class AnnouncementProperties {

  private int maxActive = 20;
  private int maxLength = 500;
  private Duration defaultTtl = Duration.ofDays(7);

  public int getMaxActive() {
    return maxActive;
  }

  public void setMaxActive(int maxActive) {
    this.maxActive = maxActive;
  }

  public int getMaxLength() {
    return maxLength;
  }

  public void setMaxLength(int maxLength) {
    this.maxLength = maxLength;
  }

  public Duration getDefaultTtl() {
    return defaultTtl;
  }

  public void setDefaultTtl(Duration defaultTtl) {
    this.defaultTtl = defaultTtl;
  }
}

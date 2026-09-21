package tacos.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provides the business {@link Clock} used by date-sensitive rules (TC-15
 * coupon windows, and any future expiration). Tests and integrations can
 * replace this bean with a fixed clock to make boundaries deterministic.
 */
@Configuration
public class BusinessClockConfig {

  @Bean
  public Clock businessClock() {
    return Clock.systemDefaultZone();
  }

}
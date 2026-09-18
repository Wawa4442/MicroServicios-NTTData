package tacos.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

/**
 * External pricing policy (TC-14): currency, base taco fee, rounding and the
 * maximum quantity per line. Commercial values live in configuration, not in
 * code, so they can change without a rebuild.
 */
@Component
@ConfigurationProperties(prefix = "tacos.pricing")
@Data
public class PricingProperties {

  private String currency = "USD";

  /** Fixed fee added to every taco on top of its ingredient prices. */
  private BigDecimal baseTacoFee = new BigDecimal("1.00");

  private int maxLineQuantity = 20;

  private RoundingMode rounding = RoundingMode.HALF_UP;

  private int scale = 2;

}
package tacos.coupon;

import java.math.BigDecimal;
import java.time.LocalDate;

import lombok.Data;

/**
 * One coupon of the configured catalog (TC-15). These are data, bound from
 * {@code tacos.coupons} in YAML/environment, never hardcoded business rules.
 *
 * <p>Every code is normalized (uppercase, trimmed) before lookup; the engine
 * never exposes the whole catalog to clients, so the configuration itself is
 * not a listing endpoint.
 */
@Data
public class CouponDefinition {

  private String code;

  private CouponType type;

  /** PERCENTAGE: percentage points; FIXED: amount in the order currency. */
  private BigDecimal value;

  /** Orders below this subtotal cannot use the coupon. Null means no minimum. */
  private BigDecimal minSubtotal;

  /** Absolute cap on the computed discount. Null means no cap. */
  private BigDecimal maxDiscount;

  /** First day (inclusive) the coupon is valid. Null means no start. */
  private LocalDate activeFrom;

  /** Last day (inclusive) the coupon is valid. Null means no end. */
  private LocalDate activeTo;

}
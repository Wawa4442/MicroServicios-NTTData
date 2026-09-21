package tacos.coupon;

import java.math.BigDecimal;

import lombok.Value;

/**
 * Immutable verdict of the coupon engine for one code + subtotal pair:
 * whether it applies, how much it discounts (never negative, never above the
 * subtotal) and a human explanation of the decision.
 */
@Value
public class CouponDecision {

  private final CouponStatus status;
  private final String normalizedCode;
  private final BigDecimal discount;
  private final String detail;

  public static CouponDecision applied(String code, BigDecimal discount) {
    return new CouponDecision(CouponStatus.APPLIED, code, discount,
        "Coupon applied.");
  }

  public static CouponDecision rejected(CouponStatus status, String code, String detail) {
    return new CouponDecision(status, code, BigDecimal.ZERO, detail);
  }

}
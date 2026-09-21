package tacos.coupon;

import java.math.BigDecimal;

import lombok.Value;

/**
 * Answer of the coupon validation endpoint. Always 200: the endpoint is a
 * query, not an effect, so a rejected code is a decision, not an exception.
 * Only the code the client sent is answered; the catalog is never enumerated.
 */
@Value
public class CouponValidateResponse {

  private final String code;
  private final CouponStatus status;
  private final boolean valid;
  private final BigDecimal discount;
  private final String detail;

  public static CouponValidateResponse from(String normalizedRawCode,
      CouponDecision decision) {
    return new CouponValidateResponse(normalizedRawCode, decision.getStatus(),
        decision.getStatus() == CouponStatus.APPLIED,
        decision.getDiscount(), decision.getDetail());
  }

}
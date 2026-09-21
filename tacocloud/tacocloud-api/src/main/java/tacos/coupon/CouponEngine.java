package tacos.coupon;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import org.springframework.stereotype.Service;

/**
 * Coupon rules engine (TC-15). Decides, for one code and one subtotal,
 * whether the promotion applies today and how much money it takes off.
 *
 * <p>Design rules honored here:
 * <ul>
 *   <li>codes are normalized (trim + uppercase) before lookup;</li>
 *   <li>an unknown code and an expired one are both denied, but each has its
 *       own stable status: the client keeps a defined answer without the
 *       endpoint ever listing which codes exist;</li>
 *   <li>dates come from an injected {@link Clock} — never {@code LocalDate.now()}
 *       — so expiration boundaries are deterministic in tests;</li>
 *   <li>the discount can never exceed the subtotal, so the total can never go
 *       negative regardless of percentages;</li>
 *   <li>the engine keeps no state: applying the same coupon twice to the same
 *       quote yields the same discount twice (it never compounds).</li>
 * </ul>
 */
@Service
public class CouponEngine {

  private static final int MONEY_SCALE = 2;
  private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

  private final Map<String, CouponDefinition> byCode;
  private final Clock clock;

  public CouponEngine(CouponProperties properties, Clock clock) {
    this.byCode = new HashMap<>();
    for (CouponDefinition coupon : properties.getCoupons()) {
      if (coupon.getCode() != null && !coupon.getCode().trim().isEmpty()) {
        byCode.put(normalize(coupon.getCode()), coupon);
      }
    }
    this.clock = clock;
  }

  /**
   * Verifies a code without throwing. Use for the validate/quote endpoint.
   */
  public CouponDecision evaluate(BigDecimal subtotal, String rawCode) {
    String code = normalize(rawCode);
    if (code.isEmpty()) {
      return CouponDecision.rejected(CouponStatus.UNKNOWN_CODE, code,
          "A coupon code is required.");
    }
    CouponDefinition coupon = byCode.get(code);
    if (coupon == null) {
      return CouponDecision.rejected(CouponStatus.UNKNOWN_CODE, code,
          "The code was not recognized.");
    }
    LocalDate today = LocalDate.now(clock);
    if (coupon.getActiveFrom() != null && today.isBefore(coupon.getActiveFrom())) {
      return CouponDecision.rejected(CouponStatus.NOT_STARTED, code,
          "This coupon is not active yet.");
    }
    if (coupon.getActiveTo() != null && today.isAfter(coupon.getActiveTo())) {
      return CouponDecision.rejected(CouponStatus.EXPIRED, code,
          "This coupon has expired.");
    }
    if (coupon.getMinSubtotal() != null && subtotal.compareTo(coupon.getMinSubtotal()) < 0) {
      return CouponDecision.rejected(CouponStatus.MINIMUM_NOT_MET, code,
          "The order subtotal does not reach the coupon minimum.");
    }
    return CouponDecision.applied(code, discountOf(coupon, subtotal));
  }

  /**
   * Applies a code to an order in progress. A non-applicable code aborts the
   * order with {@link CouponNotApplicableException}: silently ignoring a promo
   * the client typed would lose honest money.
   */
  public CouponDecision apply(BigDecimal subtotal, String rawCode) {
    CouponDecision decision = evaluate(subtotal, rawCode);
    if (decision.getStatus() != CouponStatus.APPLIED) {
      throw new CouponNotApplicableException(decision);
    }
    return decision;
  }

  private BigDecimal discountOf(CouponDefinition coupon, BigDecimal subtotal) {
    BigDecimal subtotalMoney = money(subtotal);
    BigDecimal raw;
    if (coupon.getType() == CouponType.PERCENTAGE) {
      raw = subtotalMoney.multiply(money(coupon.getValue()))
          .divide(new BigDecimal("100"), MONEY_SCALE, ROUNDING);
    } else {
      raw = money(coupon.getValue());
    }
    if (coupon.getMaxDiscount() != null
        && raw.compareTo(money(coupon.getMaxDiscount())) > 0) {
      raw = money(coupon.getMaxDiscount());
    }
    if (raw.compareTo(subtotalMoney) > 0) {
      raw = subtotalMoney;
    }
    return raw;
  }

  private static String normalize(String raw) {
    return raw == null ? "" : raw.trim().toUpperCase();
  }

  private static BigDecimal money(BigDecimal value) {
    return value == null
        ? BigDecimal.ZERO
        : value.setScale(MONEY_SCALE, ROUNDING);
  }

}
package tacos.coupon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

/**
 * TC-15 coupon engine decisions. A fixed {@link Clock} makes every date window
 * boundary deterministic: the engine never calls {@code LocalDate.now()}.
 */
public class CouponEngineTest {

  private static final LocalDate ON_JUNE_15 = LocalDate.of(2025, 6, 15);
  private static final LocalDate STARTS_JUNE_1 = LocalDate.of(2025, 6, 1);
  private static final LocalDate ENDS_JUNE_30 = LocalDate.of(2025, 6, 30);

  @Test
  public void percentage_discount_isDebitScaleHalfUp() {
    CouponEngine engine = engine(clockAt(ON_JUNE_15), newCoupon("P10",
        CouponType.PERCENTAGE, "10", null, null, null, null));

    CouponDecision decision = engine.evaluate(money("100.00"), "P10");

    assertEquals(CouponStatus.APPLIED, decision.getStatus());
    assertEquals(0, money("10.00").compareTo(decision.getDiscount()));
  }

  @Test
  public void percentage_roundsHalfUp_onSmallSubtotals() {
    CouponEngine engine = engine(clockAt(ON_JUNE_15), newCoupon("P10",
        CouponType.PERCENTAGE, "10", null, null, null, null));

    CouponDecision decision = engine.evaluate(money("1.33"), "P10");

    assertEquals(0, money("0.13").compareTo(decision.getDiscount()),
        "1.33 * 10% = 0.133, which rounds half-up to 0.13");
  }

  @Test
  public void fixed_discount_isAFlatMoneyAmount() {
    CouponEngine engine = engine(clockAt(ON_JUNE_15), newCoupon("F5",
        CouponType.FIXED, "5.00", null, null, null, null));

    CouponDecision decision = engine.evaluate(money("100.00"), "F5");

    assertEquals(0, money("5.00").compareTo(decision.getDiscount()));
  }

  @Test
  public void maxDiscount_capsTheComputedDiscount() {
    // 100.00 * 10% = 10.00, but the coupon caps at 5.00.
    CouponDecision decision = engine(clockAt(ON_JUNE_15), p10())
        .evaluate(money("100.00"), "P10");

    assertEquals(0, money("5.00").compareTo(decision.getDiscount()));
  }

  @Test
  public void discount_neverExceedsSubtotal_totalCannotGoNegative() {
    CouponEngine engine = engine(clockAt(ON_JUNE_15), newCoupon("F20",
        CouponType.FIXED, "20.00", null, null, null, null));

    CouponDecision decision = engine.evaluate(money("3.99"), "F20");

    assertEquals(CouponStatus.APPLIED, decision.getStatus());
    assertEquals(0, money("3.99").compareTo(decision.getDiscount()),
        "the discount is clamped to the subtotal");
    assertTrue(money("3.99").subtract(decision.getDiscount()).signum() >= 0,
        "the resulting total is never negative");
  }

  @Test
  public void activeFrom_isInclusive() {
    CouponDecision decision = engine(clockAt(STARTS_JUNE_1), p10())
        .evaluate(money("100.00"), "P10");

    assertEquals(CouponStatus.APPLIED, decision.getStatus(),
        "a coupon can be used on its own start date");
  }

  @Test
  public void activeTo_isInclusive() {
    CouponDecision decision = engine(clockAt(ENDS_JUNE_30), p10())
        .evaluate(money("100.00"), "P10");

    assertEquals(CouponStatus.APPLIED, decision.getStatus(),
        "a coupon is valid on its own end date");
  }

  @Test
  public void notStarted_dateWindow_returnsNotStarted() {
    CouponDecision decision = engine(clockAt(LocalDate.of(2025, 5, 31)), p10())
        .evaluate(money("100.00"), "P10");

    assertEquals(CouponStatus.NOT_STARTED, decision.getStatus());
    assertEquals(0, BigDecimal.ZERO.compareTo(decision.getDiscount()));
  }

  @Test
  public void expired_dateWindow_returnsExpired() {
    CouponDecision decision = engine(clockAt(LocalDate.of(2025, 7, 1)), p10())
        .evaluate(money("100.00"), "P10");

    assertEquals(CouponStatus.EXPIRED, decision.getStatus());
    assertEquals(0, BigDecimal.ZERO.compareTo(decision.getDiscount()));
  }

  @Test
  public void minimumSubtotal_notReached_returnsMinimumNotMet() {
    CouponDecision decision = engine(clockAt(ON_JUNE_15), p10())
        .evaluate(money("9.99"), "P10");

    assertEquals(CouponStatus.MINIMUM_NOT_MET, decision.getStatus());
  }

  @Test
  public void minimumSubtotal_exactlyReached_isApplied() {
    CouponDecision decision = engine(clockAt(ON_JUNE_15), p10())
        .evaluate(money("10.00"), "P10");

    assertEquals(CouponStatus.APPLIED, decision.getStatus());
  }

  @Test
  public void unknownCode_isDeniedWithStableStatus() {
    CouponDecision decision = engine(clockAt(ON_JUNE_15), p10())
        .evaluate(money("100.00"), "NOEXISTE");

    assertEquals(CouponStatus.UNKNOWN_CODE, decision.getStatus());
    assertEquals(0, BigDecimal.ZERO.compareTo(decision.getDiscount()));
  }

  @Test
  public void codes_areNormalized_trimAndUppercase() {
    CouponDecision decision = engine(clockAt(ON_JUNE_15), p10())
        .evaluate(money("100.00"), "  p10  ");

    assertEquals(CouponStatus.APPLIED, decision.getStatus());
    assertEquals("P10", decision.getNormalizedCode());
  }

  @Test
  public void blankCode_isDeniedWithUnknownStatus() {
    CouponDecision decision = engine(clockAt(ON_JUNE_15), p10())
        .evaluate(money("100.00"), "   ");

    assertEquals(CouponStatus.UNKNOWN_CODE, decision.getStatus());
  }

  @Test
  public void nullCode_isDenied() {
    CouponDecision decision = engine(clockAt(ON_JUNE_15), p10())
        .evaluate(money("100.00"), null);

    assertEquals(CouponStatus.UNKNOWN_CODE, decision.getStatus());
  }

  @Test
  public void engineIsStateless_sameQuoteDiscountTwice_noCompounding() {
    CouponEngine engine = engine(clockAt(ON_JUNE_15),
        newCoupon("P50", CouponType.PERCENTAGE, "50", null, null, null, null));

    BigDecimal once = engine.evaluate(money("100.00"), "P50").getDiscount();
    BigDecimal again = engine.evaluate(money("100.00"), "P50").getDiscount();

    assertEquals(0, once.compareTo(again),
        "a stateless engine returns the same discount twice; nothing compounds");
  }

  @Test
  public void apply_throwsCouponNotApplicableException_withDecisionStatus() {
    CouponEngine engine = engine(clockAt(ON_JUNE_15), p10());

    CouponNotApplicableException ex = assertThrows(
        CouponNotApplicableException.class, () -> engine.apply(money("100.00"), "ZZZ"));

    assertEquals(CouponStatus.UNKNOWN_CODE, ex.getStatus());
    assertTrue(ex.getDecision().getNormalizedCode().equals("ZZZ"));
  }

  // ------------------------------------------------------------------
  // fixtures
  // ------------------------------------------------------------------

  private static CouponEngine engine(Clock clock, CouponDefinition... coupons) {
    CouponProperties properties = new CouponProperties();
    for (CouponDefinition coupon : coupons) {
      properties.getCoupons().add(coupon);
    }
    return new CouponEngine(properties, clock);
  }

  /** The demo promotion: 10% off orders >= 10.00, capped at 5.00, June window. */
  private static CouponDefinition p10() {
    return newCoupon("P10", CouponType.PERCENTAGE, "10", "10.00", "5.00",
        STARTS_JUNE_1, ENDS_JUNE_30);
  }

  private static CouponDefinition newCoupon(String code, CouponType type, String value,
      String minSubtotal, String maxDiscount, LocalDate from, LocalDate to) {
    CouponDefinition coupon = new CouponDefinition();
    coupon.setCode(code);
    coupon.setType(type);
    coupon.setValue(money(value));
    coupon.setMinSubtotal(minSubtotal == null ? null : money(minSubtotal));
    coupon.setMaxDiscount(maxDiscount == null ? null : money(maxDiscount));
    coupon.setActiveFrom(from);
    coupon.setActiveTo(to);
    return coupon;
  }

  private static Clock clockAt(LocalDate date) {
    return Clock.fixed(date.atTime(12, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
  }

  private static BigDecimal money(String value) {
    return new BigDecimal(value);
  }

}
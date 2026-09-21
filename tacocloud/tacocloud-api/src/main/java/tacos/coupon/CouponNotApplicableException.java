package tacos.coupon;

/**
 * Raised when an order is submitted with a coupon code that the engine cannot
 * apply. Carries the decision so the HTTP mapping can explain the exact reason
 * (unknown, expired, not started or minimum not met) with a stable code.
 */
public class CouponNotApplicableException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final CouponDecision decision;

  public CouponNotApplicableException(CouponDecision decision) {
    super(decision.getDetail());
    this.decision = decision;
  }

  public CouponDecision getDecision() {
    return decision;
  }

  public CouponStatus getStatus() {
    return decision.getStatus();
  }

}
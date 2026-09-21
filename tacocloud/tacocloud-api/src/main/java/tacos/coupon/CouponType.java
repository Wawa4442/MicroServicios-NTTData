package tacos.coupon;

/**
 * How a coupon computes its discount.
 *
 * <p>{@code PERCENTAGE} subtracts a percent of the order subtotal (capped by
 * the coupon's {@code maxDiscount}, when present); {@code FIXED} subtracts a
 * fixed amount that can never exceed the subtotal itself.
 */
public enum CouponType {
  PERCENTAGE,
  FIXED
}
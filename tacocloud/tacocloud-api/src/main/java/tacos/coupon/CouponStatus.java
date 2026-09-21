package tacos.coupon;

import java.math.BigDecimal;

/**
 * Result of verifying a single code against a subtotal. The engine never
 * throws for a bad code; it returns a decision with a stable status so the
 * caller (endpoint or order creation) can render it consistently.
 *
 * <p>Codes are normalized case-insensitively. {@code UNKNOWN_CODE} and
 * {@code EXPIRED} are intentionally distinct but never enumerate the coupon
 * catalog through HTTP: they only answer about the code the client sent.
 */
public enum CouponStatus {
  APPLIED,
  UNKNOWN_CODE,
  NOT_STARTED,
  EXPIRED,
  MINIMUM_NOT_MET
}
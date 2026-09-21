package tacos.coupon;

import java.math.BigDecimal;

import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

import lombok.Data;

/**
 * Body of {@code POST /api/coupons/validate}: a proposed discount code and the
 * subtotal the customer thinks their order has. The real subtotal for an
 * order is always recomputed server side (TC-14); this contract only exists to
 * validate a code without creating an order.
 */
@Data
public class CouponValidateRequest {

  @NotBlank(message = "code is required")
  private String code;

  @NotNull(message = "subtotal is required")
  @DecimalMin(value = "0.0", message = "subtotal must not be negative")
  private BigDecimal subtotal;

}